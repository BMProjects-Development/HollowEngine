package ru.hollowhorizon.hollowengine.client.models.internal.v2

import kotlinx.coroutines.flow.StateFlow
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Model
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
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.max
import kotlin.math.min

/**
 * The model at [model] or fallback.
 */
fun ModelAttachment(model: String): ModelAttachment {
    val location = ResourceLocation.tryParse(model) ?: run {
        HollowEngine.LOGGER.warn("Model path '{}' is not a valid resource location, using the fallback", model)
        ModelLoader.FALLBACK_MODEL
    }
    return ModelAttachment(location)
}

fun ModelAttachment(location: ResourceLocation): ModelAttachment {
    return ModelAttachment(HollowModelManager.getOrCreate(location), null, location = location)
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
    private var nodesByIndex: Map<Int, RuntimeNode> = emptyMap()
    private var runtimeMaterials = ModelInstanceMaterials(Model.EMPTY)
    private var renderPipeline = ListRenderPipeline()
    private var target: PoseTarget? = null
    private var cachedBounds: Pair<Vec3f, Vec3f>? = null
    private val modelChangeListeners = ArrayList<() -> Unit>()

    val model: Model get() = builtFor ?: Model.EMPTY
    val nodes: List<RuntimeNode> get() = runtimeNodes
    val animations: Collection<AnimationClip> get() = model.animations
    val materials: List<Material> get() = runtimeMaterials.values
    val pipeline: RenderPipeline get() = renderPipeline
    val isFrustumCullingEnabled: Boolean get() = HollowModelManager.metadata(location).frustumCulling

    /**
     * What is authored onto this model's bones.
     */
    var rig: ModelRig
        get() = currentRig
        set(value) {
            authoredRig = value
            if (currentRig == value) return

            currentRig = value
            builtFor?.let(::rebuild)
        }

    private var currentRig: ModelRig = RigAssets.of(location)

    private var authoredRig: ModelRig? = null

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
        cachedBounds = null
    }

    /**
     * Dresses this instance's materials, by the names the model gave them.
     */
    fun applyMaterials(overrides: Map<String, MaterialSource>) = runtimeMaterials.apply(overrides)


    /** What a pose is written into: this instance's nodes and the clips of its model. */
    fun poseTarget(): PoseTarget = target ?: PoseTarget(
        nodesByIndex = nodesByIndex,
        animations = model.animationsByName,
        aliases = rig.boneByAlias,
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
        runtimeNodes = model.scenes.getOrNull(model.scene)?.nodes?.map {
            RuntimeNode(it, this, runtimeMaterials::resolve)
        } ?: emptyList()
        nodesByIndex = runtimeNodes.byIndex()
        nodesByIndex.values.forEach(::customizeNode)
        renderPipeline = ListRenderPipeline().apply(this::collectCommands)
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

        val context = RigAttachmentContext(node) { entity }
        bone.attachments.forEach { spec ->
            RigAttachmentFactories.create(spec, context)?.let(node.attachments::add)
        }
        if (bone.hidden) node.isVisible = false
    }


    override fun collectCommands(pipeline: RenderPipeline) {
        super.collectCommands(pipeline)
        runtimeNodes.forEach { it.collectCommands(pipeline) }
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
