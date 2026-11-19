package ru.hollowhorizon.hollowengine.client.models.internal.v2

import com.mojang.blaze3d.systems.RenderSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4dc
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.extrusion.MeshExtrusionAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelLoader
import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.ListRenderPipeline
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.StaticSubmissions
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.submitStatic
import ru.hollowhorizon.hollowengine.common.models.MaterialSource
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.rigId
import ru.hollowhorizon.hollowengine.common.scene.ScenePathSegment
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import java.util.*
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
) : BaseAttachment(parent) {
    private val renderScene = ModelRenderScene.create(parent, flow.value)
    private var rebuildingNested: ModelNestedAttachments? = null
    val sceneRoot: SceneObject get() = renderScene.root
    internal val sceneRevision: Int get() = sceneRoot.scene.revision
    internal val parentRevision: Int get() = attachmentParent?.globalVersion ?: 0
    override val transform: TrsTransformF get() = sceneRoot.localTransform
    var instanceId: String = UUID.randomUUID().toString()

    /** The entity that carries this model, living or not, for what hangs on it and talks to it, as a panel does. */
    var carrier: Entity? = null
    private var builtFor: Model? = null
    private var runtimeNodes: List<RenderModelNode> = emptyList()

    /** Every node of [runtimeNodes], parents first; the hierarchy only changes when the model is built again. */
    private var allNodes: List<RenderModelNode> = emptyList()

    /**
     * Holds what hangs on the model itself rather than on a bone. It is not one of the model's nodes, so
     * no animation or name lookup ever reaches it.
     */
    var modelRoot: RenderModelNode? = null
        private set
    private var nodesByIndex: Map<Int, RenderModelNode> = emptyMap()
    internal val scenePoseNodes get() = renderScene.instance.poseNodesByIndex
    private var runtimeMaterials = ModelInstanceMaterials(Model.EMPTY)
    private var renderPipeline: ListRenderPipeline? = null
    private var cachedBounds: Pair<Vec3f, Vec3f>? = null
    private var keptCullingBounds: Pair<Vec3f, Vec3f>? = null
    private var posesSinceCullingBounds = 0

    /** Changes with every pose, so what is worked out from one can be kept until the next. */
    var poseVersion = 0
        private set
    private val modelChangeListeners = ArrayList<() -> Unit>()

    val model: Model get() = builtFor ?: Model.EMPTY
    val nodes: List<RenderModelNode> get() = runtimeNodes
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
            if (sceneRoot.isRemoved) return
            if (currentRig == value) return

            val materialsChanged = currentRig.materials != value.materials
            if (canUpdateRig(currentRig, value, allNodes, modelRoot)) {
                renderScene.instance.updateRigBoneRestTransforms(currentRig, value)
                currentRig = value
                rigCarriesEffects = value.carriesLegacyEffects()
                respecAttachments()
                renderScene.respecComponents(value)
                if (materialsChanged) dress()
                cachedBounds = null
                keptCullingBounds = null
            } else {
                builtFor?.let(::rebuild)
            }
        }

    private fun respecAttachments() {
        fun update(node: RenderModelNode, specs: List<Any>) {
            for (running in node.attachments) {
                if (running !is RespecAttachment) continue
                val spec = specs.firstOrNull { it.rigId == running.spec.rigId } ?: continue
                if (running.spec != spec) running.respec(spec)
            }
        }
        for (node in allNodes) {
            val specs = currentRig.bone(node.name)?.attachments ?: continue
            update(node, specs)
        }
        modelRoot?.let { update(it, currentRig.attachments) }
    }

    private var currentRig: ModelRig = RigAssets.of(location)

    private var authoredRig: ModelRig? = null

    /** What the entity dresses this model in, under what the rig says about each material. */
    private var dressedBy: Map<String, MaterialSource> = emptyMap()

    /** Whether any effect hangs on this model, here or on a model nested in it; effects reach past any box. */
    private var rigCarriesEffects = false
    val carriesEffects: Boolean get() = renderScene.clientContext?.hasDraws == true || rigCarriesEffects

    internal fun updatePreviewComponents(
        playSounds: Boolean, effectTime: Float?, effectEmitting: ((String?, String, Float) -> Boolean)?,
    ) {
        renderScene.clientContext?.updatePreview(playSounds, effectTime, effectEmitting)
    }

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
        if (sceneRoot.isRemoved) return
        val current = flow.value
        if (builtFor !== current) rebuild(current)
    }

    /** Puts every node back in "T-pose"; the animator then poses on top. */
    fun beginPose() {
        ensureReady()
        renderScene.instance.resetPose()
    }

    /** Resolves the imported rig before gameplay constraints can override it. */
    fun applyAuthoredConstraints() = renderScene.instance.applyAuthoredConstraints()

    internal fun applyRigPose() = renderScene.instance.applyRigPose(rig)

    private val scaleMeshes: (SceneObject, Vec3f) -> Unit = { pose, scale ->
        val node = nodesByIndex[pose.poseIndex]
        if (node != null && node.definition.skin == null) {
            for (mesh in node.attachments) {
                if (mesh !is MeshAttachment) continue
                mesh.transform.scale.set(scale)
                mesh.transform.markDirty()
            }
        }
    }

    internal fun applyRigConstraints(context: AnimatorEvaluationContext, grounding: RigGroundingState? = null) =
        renderScene.instance.applyRigConstraints(rig, context, grounding, scaleMeshes)

    /** Turns the posed nodes into the matrices the renderer draws from. */
    fun endPose() {
        applyAuthoredConstraints()
        updateGlobalMatrix()
        allNodes.forEach(RenderModelNode::updateAttachmentMatrices)
        modelRoot?.updateAttachmentMatrices()
        cachedBounds = null
        posesSinceCullingBounds++
        poseVersion++
    }

    internal fun refreshScenePose() {
        renderScene.instance.invalidateConstraints()
        endPose()
    }

    /** Drops the draw commands, which something hung on this model changed under; they are collected again on the next draw. */
    fun invalidatePipeline() {
        renderPipeline = null
        generateSequence(attachmentParent) { it.attachmentParent }.filterIsInstance<ModelAttachment>().firstOrNull()?.invalidatePipeline()
    }

    /**
     * Dresses this instance's materials, by the names the model gave them.
     */
    fun applyMaterials(overrides: Map<String, MaterialSource>) {
        dressedBy = overrides
        dress()
    }

    private fun dress() {
        runtimeMaterials.apply(dressedBy + currentRig.materials)
        appearanceChanged()
    }

    /** Changes when a node is shown or hidden or the materials are dressed, so what is drawn has to be gathered again. */
    var appearanceVersion = 0
        private set

    internal fun appearanceChanged() {
        appearanceVersion++
    }


    /** What a pose is written into: this instance's nodes and the clips of its model. */
    fun poseTarget(): PoseTarget = renderScene.instance.poseTarget(rig)

    /**
     * Runs [listener] when newly loaded model replaces older one.
     */
    fun onModelChange(listener: () -> Unit) {
        modelChangeListeners += listener
    }

    private fun rebuild(model: Model) {
        val nextRig = authoredRig ?: RigAssets.of(location)
        renderScene.prepare(model, nextRig)
        ModelNestedAttachments(allNodes, modelRoot).use { retained ->
            rebuildingNested = retained
            try {
                rebuildContents(model, nextRig)
            } finally {
                rebuildingNested = null
            }
        }
    }

    private fun rebuildContents(model: Model, nextRig: ModelRig) {
        // Scene reconciliation can move nodes away from their former render parents.
        allNodes.asReversed().forEach(RenderModelNode::close)
        modelRoot?.close()
        currentRig = nextRig
        rigCarriesEffects = nextRig.carriesLegacyEffects()
        builtFor = model
        runtimeMaterials = ModelInstanceMaterials(model)
        runtimeNodes = renderScene.bindNodes(this, runtimeMaterials::resolve)
        allNodes = runtimeNodes.flatMap { it.walk() }
        nodesByIndex = allNodes.associateBy { it.definition.index }
        nodesByIndex.values.forEach(::customizeNode)
        modelRoot = bindLegacyRoot()
        renderScene.bindComponents(nextRig)
        dress()
        invalidatePipeline()
        cachedBounds = null
        keptCullingBounds = null
        modelChangeListeners.forEach { it() }
    }

    override fun close() {
        try {
            allNodes.asReversed().forEach(RenderModelNode::close)
            modelRoot?.close()
        } finally {
            runtimeNodes = emptyList()
            allNodes = emptyList()
            modelRoot = null
            renderPipeline = null
            nodesByIndex = emptyMap()
            renderScene.close()
        }
    }

    internal fun sceneNodeRemoved(node: RenderModelNode) {
        val wasModelRoot = modelRoot === node
        if (wasModelRoot) modelRoot = null
        if (runtimeNodes.any { it === node }) runtimeNodes = runtimeNodes.filterNot { it === node }
        if (wasModelRoot || allNodes.any { it === node }) {
            allNodes = allNodes.filterNot { it === node }
            if (nodesByIndex[node.definition.index] === node) nodesByIndex = nodesByIndex - node.definition.index
            renderScene.instance.forgetPose(node.definition.index, node.sceneObject)
            cachedBounds = null
            keptCullingBounds = null
            invalidatePipeline()
        }
    }

    internal val hasComponentUpdates: Boolean get() = renderScene.clientContext?.hasUpdates == true
    internal val readsComponentAnimation: Boolean get() = renderScene.clientContext?.needsAnimationVariables == true

    internal fun updateWorldComponents(modelToWorld: Matrix4dc, entity: Entity, camera: Vec3, animation: AnimatorEvaluationContext?) {
        renderScene.clientContext?.updateWorld(modelToWorld, entity, instanceId, camera, animation)
    }

    /**
     * Hangs on the node whatever the rig says belongs there.
     */
    private fun customizeNode(node: RenderModelNode) {
        val bone = rig.bone(node.name)
        if (bone == null) {
            when (node.name) {
                "RightHandItem" -> node.attachments.add(ItemNode({ entity }, EquipmentSlot.MAINHAND, node))
                "LeftHandItem" -> node.attachments.add(ItemNode({ entity }, EquipmentSlot.OFFHAND, node))
            }
            return
        }

        if (bone.hidden) node.isVisible = false
        bone.material?.let { name ->
            node.attachments.replaceAll { mesh ->
                if (mesh is MeshAttachment) MeshAttachment(mesh.primitive, node, runtimeMaterials.named(name, mesh.material)) else mesh
            }
        }
        val context = RigAttachmentContext(node, { entity }, this) { carrier }
        bone.attachments.forEach { spec -> createRigAttachment(spec, context)?.let(node.attachments::add) }
    }

    private fun createRigAttachment(spec: Any, context: RigAttachmentContext): Attachment? =
        rebuildingNested?.reuse(spec, context) ?: RigAttachmentFactories.create(spec, context)

    private fun bindLegacyRoot(): RenderModelNode? {
        if (currentRig.attachments.none(RigAttachmentFactories::canCreate)) return null
        val definition = NodeDefinition(MODEL_ROOT_INDEX, MODEL_ROOT, mutableListOf(), TrsTransformF())
        val root = RenderModelNode(sceneRoot, definition, this) { it }
        val context = RigAttachmentContext(root, { entity }, this) { carrier }
        currentRig.attachments.forEach { spec -> createRigAttachment(spec, context)?.let(root.attachments::add) }
        return root
    }


    override fun collectCommands(pipeline: RenderPipeline) {
        super.collectCommands(pipeline)
        collectStaticMeshes(pipeline)
        allNodes.forEach { it.collectCommands(pipeline) }
        modelRoot?.collectCommands(pipeline)
        val context = renderScene.clientContext
        if (context != null) pipeline.addBatchedRenderable { context.collect(stack.last().pose()) }
    }

    /** Hands the unskinned meshes of this instance to the model's merged geometry, to be drawn as one. */
    private fun collectStaticMeshes(pipeline: RenderPipeline) {
        val geometry = model.staticGeometry ?: return
        val meshes = arrayOfNulls<MeshAttachment>(geometry.slots.size)
        val kept = StaticSubmissions(geometry)
        allNodes.forEach { node ->
            node.attachments.forEach { mesh ->
                if (mesh !is MeshAttachment || mesh.primitive.isDynamic) return@forEach
                geometry.slotOf(node.definition.index, mesh.primitive)?.let { meshes[it.index] = mesh }
            }
        }
        pipeline.addInstancedRenderable { submitStatic(geometry, meshes, kept, poseVersion, appearanceVersion) }
    }

    companion object {
        /** The name of the node that holds what hangs on the model itself. */
        const val MODEL_ROOT = "#model"
        private const val MODEL_ROOT_INDEX = -2

        /** How many poses the culling bounds are kept for before they are worked out again. */
        private const val CULLING_BOUNDS_POSES = 4
    }

    fun child(name: String): RenderModelNode {
        ensureReady()
        return runtimeNodes.single { it.name == name }
    }

    fun findNode(name: String): RenderModelNode? {
        ensureReady()
        return allNodes.firstOrNull { it.name == name }
    }

    /** Resolves an authored path inside this model instance. */
    fun findNode(path: List<ScenePathSegment>): RenderModelNode? {
        ensureReady()
        return renderScene.instance.node(path)?.observer as? RenderModelNode
    }

    /** The instance's bounds in its own space, recomputed after every pose. */
    fun calculateBounds(): Pair<Vec3f, Vec3f>? = cachedBounds ?: calculateBoundsInternal().also { cachedBounds = it }

    /**
     * The bounds a renderer culls the instance by: [calculateBounds] of a pose a few poses old at most, as a pose
     * moves little from one frame to the next and working them out takes every corner of every mesh.
     */
    fun cullingBounds(): Pair<Vec3f, Vec3f>? {
        keptCullingBounds?.takeIf { posesSinceCullingBounds < CULLING_BOUNDS_POSES }?.let { return it }
        posesSinceCullingBounds = 0
        return calculateBounds().also { keptCullingBounds = it }
    }

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
            val margin = runtimeNode.attachments.firstNotNullOfOrNull { (it as? MeshExtrusionAttachment)?.boundsMargin } ?: 0f
            runtimeNode.definition.mesh?.primitives?.forEach { primitive ->
                val localBounds = primitive.localBounds ?: return@forEach
                val min = localBounds.first - margin
                val max = localBounds.second + margin

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

private fun canUpdateRig(
    current: ModelRig,
    next: ModelRig,
    nodes: List<RenderModelNode>,
    root: RenderModelNode?,
): Boolean {
    if (current.bones.keys != next.bones.keys) return false
    if (!canUpdateAttachments(current.attachments, next.attachments)) return false
    for ((name, before) in current.bones) {
        val after = next.bones.getValue(name)
        if (before.alias != after.alias || before.hidden != after.hidden || before.material != after.material) return false
        if ((before.origin == null) != (after.origin == null) || before.origin?.parent != after.origin?.parent) return false
        if (!canUpdateAttachments(before.attachments, after.attachments)) return false
    }
    return (root == null || root.canUpdateAttachments(current.attachments, next.attachments)) &&
        nodes.all { node ->
            val oldBone = current.bone(node.name)
            oldBone == null || node.canUpdateAttachments(oldBone.attachments, next.bones.getValue(node.name).attachments)
        }
}

private fun canUpdateAttachments(current: List<Any>, next: List<Any>): Boolean =
    current.size == next.size && current.indices.all { index ->
        val before = current[index]
        val after = next[index]
        before === after || (before::class == after::class && before.rigId == after.rigId)
    }

private fun RenderModelNode.canUpdateAttachments(current: List<Any>, next: List<Any>): Boolean =
    current.indices.all { index ->
        val before = current[index]
        val after = next[index]
        before == after || !RigAttachmentFactories.canCreate(after) ||
            attachments.any { it is RespecAttachment && it.spec.rigId == after.rigId && it.canRespec(after) }
    }
