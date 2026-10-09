package ru.hollowhorizon.hollowengine.client.models.internal.v2

import com.mojang.blaze3d.systems.RenderSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.animator.byIndex
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelLoader
import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.ListRenderPipeline
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.common.models.MaterialSource
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.addedBones
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.max
import kotlin.math.min

/**
 * The model at [model] or fallback; a blank path is a model with no geometry, which only carries what
 * hangs on the model itself.
 */
fun ModelAttachment(model: String, parent: Attachment? = null): ModelAttachment {
    if (model.isBlank()) return ModelAttachment(MutableStateFlow(Model.EMPTY), parent)
    val location = ResourceLocation.tryParse(model) ?: run {
        HollowEngine.LOGGER.warn("Model path '{}' is not a valid resource location, using the fallback", model)
        ModelLoader.FALLBACK_MODEL
    }
    return ModelAttachment(location, parent)
}

fun ModelAttachment(location: ResourceLocation, parent: Attachment? = null): ModelAttachment {
    return ModelAttachment(HollowModelManager.getOrCreate(location), parent, location = location)
}

/**
 * One rendered instance of a model: its own nodes, materials and draw commands.
 */
class ModelAttachment(
    val flow: StateFlow<Model>,
    parent: Attachment?,
    var entity: LivingEntity? = null,
    val location: ResourceLocation? = null,
) : Attachment(parent) {
    private var builtFor: Model? = null
    private var runtimeNodes: List<RuntimeNode> = emptyList()

    /**
     * Holds what hangs on the model itself rather than on a bone. It is not one of the model's nodes, so
     * no animation or name lookup ever reaches it.
     */
    var modelRoot: RuntimeNode? = null
        private set
    private var nodesByIndex: Map<Int, RuntimeNode> = emptyMap()
    private var runtimeMaterials = ModelInstanceMaterials(Model.EMPTY)
    private var renderPipeline: ListRenderPipeline? = null
    private var target: PoseTarget? = null
    private var cachedBounds: Pair<Vec3f, Vec3f>? = null
    private val modelChangeListeners = ArrayList<() -> Unit>()

    val model: Model get() = builtFor ?: Model.EMPTY
    val nodes: List<RuntimeNode> get() = runtimeNodes
    val animations: Collection<AnimationClip> get() = model.animations
    val materials: List<Material> get() = runtimeMaterials.values
    /** GPU buffers are created on the render thread, the first time something draws this instance. */
    val pipeline: RenderPipeline
        get() {
            RenderSystem.assertOnRenderThread()
            return renderPipeline ?: ListRenderPipeline().apply(this::collectCommands).also { renderPipeline = it }
        }
    val isFrustumCullingEnabled: Boolean get() = HollowModelManager.metadata(location).frustumCulling

    /**
     * What is authored onto this model's bones.
     */
    var rig: ModelRig
        get() = currentRig
        set(value) {
            authoredRig = value
            if (currentRig == value) return

            val sameStructure = currentRig.structure() == value.structure()
            currentRig = value
            if (sameStructure) {
                respecAttachments()
                placeAddedBones()
                dress()
                target = null
            } else {
                builtFor?.let(::rebuild)
            }
        }

    /**
     * Moves the bones the rig adds to where it now puts them. Their place is their own rest pose, so it is set
     * there and the next pose starts from it, without building the model again.
     */
    private fun placeAddedBones() {
        val added = currentRig.addedBones
        if (added.isEmpty()) return
        runtimeNodes.forEach { root ->
            root.walk().forEach { node -> added[node.name]?.let { node.definition.baseTransform.set(it.localTransform()) } }
        }
    }

    /** Hands the running attachments their specs again, after a change that left the rig's structure as it was. */
    private fun respecAttachments() {
        val holders = runtimeNodes.flatMap { it.walk() }.map { it.name to it } + listOfNotNull(modelRoot?.let { null to it })
        holders.forEach { (bone, node) ->
            node.attachments.filterIsInstance<RespecAttachment>().forEach { running ->
                currentRig.holder(bone).attachment(running.spec.id)?.let(running::respec)
            }
        }
    }

    private var currentRig: ModelRig = RigAssets.of(location)

    private var authoredRig: ModelRig? = null

    /** What the entity dresses this model in, under what the rig says about each material. */
    private var dressedBy: Map<String, MaterialSource> = emptyMap()

    /** Whether any effect hangs on this model, here or on a model nested in it; effects reach past any box. */
    var carriesEffects: Boolean = false
        private set

    val triangles get() = model.nodes.sumOf { it.mesh?.primitives?.sumOf { p -> p.positionsCount / 3 } ?: 0 }
    val shapekeys get() = model.nodes.sumOf { it.mesh?.primitives?.sumOf { p -> p.morphTargets.size } ?: 0 }

    init {
        rebuild(flow.value)
    }

    /**
     * Makes sure the instance matches the model that is loaded right now.
     *
     * Called once a frame from the instance's own update.
     */
    fun ensureReady() {
        val current = flow.value
        if (builtFor !== current) rebuild(current)
    }

    /** Puts every node back in "T-pose"; the animator then poses on top. */
    fun beginPose() {
        ensureReady()
        runtimeNodes.forEach { it.walk().forEach(RuntimeNode::resetPose) }
    }

    /** Turns the posed nodes into the matrices the renderer draws from. */
    fun endPose() {
        updateGlobalMatrix()
        runtimeNodes.forEach(RuntimeNode::updateHierarchyMatrices)
        modelRoot?.updateHierarchyMatrices()
        cachedBounds = null
    }

    /** Drops the draw commands, which something hung on this model changed under; they are collected again on the next draw. */
    fun invalidatePipeline() {
        renderPipeline = null
    }

    /**
     * Dresses this instance's materials, by the names the model gave them.
     */
    fun applyMaterials(overrides: Map<String, MaterialSource>) {
        dressedBy = overrides
        dress()
    }

    private fun dress() = runtimeMaterials.apply(dressedBy + currentRig.materials)


    /** What a pose is written into: this instance's nodes and the clips of its model. */
    fun poseTarget(): PoseTarget = target ?: PoseTarget(
        nodesByIndex = nodesByIndex,
        animations = model.animationsByName,
        aliases = rig.boneByAlias,
        rig = rig,
    ).also { target = it }

    /**
     * Runs [listener] when newly loaded model replaces older one.
     */
    fun onModelChange(listener: () -> Unit) {
        modelChangeListeners += listener
    }

    private fun rebuild(model: Model) {
        builtFor = model
        currentRig = authoredRig ?: RigAssets.of(location)
        runtimeMaterials = ModelInstanceMaterials(model)
        runtimeNodes = addRigBones(
            model.scenes.getOrNull(model.scene)?.nodes?.map { RuntimeNode(it, this, runtimeMaterials::resolve) } ?: emptyList(),
            currentRig,
            this,
        )
        nodesByIndex = runtimeNodes.byIndex()
        nodesByIndex.values.forEach(::customizeNode)
        modelRoot = RuntimeNode(NodeDefinition(MODEL_ROOT_INDEX, MODEL_ROOT, mutableListOf(), TrsTransformF()), this).also { root ->
            val context = RigAttachmentContext(root, { entity }, this)
            currentRig.attachments.forEach { spec -> RigAttachmentFactories.create(spec, context)?.let(root.attachments::add) }
        }
        carriesEffects = currentRig.carriesEffects()
        dress()
        renderPipeline = null
        target = null
        cachedBounds = null
        modelChangeListeners.forEach { it() }
    }

    /**
     * Hangs on the node whatever the rig says belongs there.
     */
    private fun customizeNode(node: RuntimeNode) {
        val bone = rig.bone(node.name)
        if (bone == null) {
            when (node.name) {
                "RightHandItem" -> node.attachments.add(ItemNode({ entity }, EquipmentSlot.MAINHAND, node))
                "LeftHandItem" -> node.attachments.add(ItemNode({ entity }, EquipmentSlot.OFFHAND, node))
            }
            return
        }

        val context = RigAttachmentContext(node, { entity }, this)
        bone.attachments.forEach { spec ->
            RigAttachmentFactories.create(spec, context)?.let(node.attachments::add)
        }
        if (bone.hidden) node.isVisible = false
        bone.material?.let { name ->
            node.attachments.replaceAll { mesh ->
                if (mesh is MeshAttachment) MeshAttachment(mesh.primitive, node, runtimeMaterials.named(name, mesh.material)) else mesh
            }
        }
    }


    override fun collectCommands(pipeline: RenderPipeline) {
        super.collectCommands(pipeline)
        runtimeNodes.forEach { it.collectCommands(pipeline) }
        modelRoot?.collectCommands(pipeline)
    }

    companion object {
        /** The name of the node that holds what hangs on the model itself. */
        const val MODEL_ROOT = "#model"
        private const val MODEL_ROOT_INDEX = -2
    }

    fun child(name: String): RuntimeNode {
        ensureReady()
        return runtimeNodes.single { it.name == name }
    }

    fun findNode(name: String): RuntimeNode? {
        ensureReady()
        return runtimeNodes.asSequence().flatMap { it.walk().asSequence() }.firstOrNull { it.name == name }
    }

    /** The instance's bounds in its own space, recomputed after every pose. */
    fun calculateBounds(): Pair<Vec3f, Vec3f>? = cachedBounds ?: calculateBoundsInternal().also { cachedBounds = it }

    private fun calculateBoundsInternal(): Pair<Vec3f, Vec3f>? {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var hasBounds = false
        val source = MutableVec3f()
        val transformed = MutableVec3f()

        nodesByIndex.values.forEach { runtimeNode ->
            val matrix = runtimeNode.globalMatrix
            runtimeNode.definition.mesh?.primitives?.forEach { primitive ->
                val localBounds = primitive.localBounds ?: return@forEach
                val min = localBounds.first
                val max = localBounds.second

                fun update(x: Float, y: Float, z: Float) {
                    source.set(x, y, z)
                    matrix.transform(source, 1f, transformed)
                    minX = min(minX, transformed.x)
                    minY = min(minY, transformed.y)
                    minZ = min(minZ, transformed.z)
                    maxX = max(maxX, transformed.x)
                    maxY = max(maxY, transformed.y)
                    maxZ = max(maxZ, transformed.z)
                }

                update(min.x, min.y, min.z)
                update(min.x, min.y, max.z)
                update(min.x, max.y, min.z)
                update(min.x, max.y, max.z)
                update(max.x, min.y, min.z)
                update(max.x, min.y, max.z)
                update(max.x, max.y, min.z)
                update(max.x, max.y, max.z)
                hasBounds = true
            }
        }

        if (!hasBounds) return null
        return Vec3f(minX, minY, minZ) to Vec3f(maxX, maxY, maxZ)
    }
}
