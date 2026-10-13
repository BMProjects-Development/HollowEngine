package ru.hollowhorizon.hollowengine.client.vfx

import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxBeamRenderer
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxCameraShake
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxDecalNode
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxDrawList
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxMeshNode
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxModelNode
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxPlaneNode
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxPostEffectNode
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxSkyNode
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxTrailRenderer
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.common.vfx.*
import kotlin.math.sqrt

/**
 * One node of a playing effect: where it is, whether it is on, and whatever its kind does.
 */
class VfxNodeRuntime(
    val spec: VfxNodeSpec,
    val parent: VfxNodeRuntime?,
    val instance: VfxInstance,
    val expressions: VfxExpressions,
    val seed: Int,
    driven: Map<VfxProperty, Int>,
) {
    val frame = VfxFrame()

    /** What the author wrote for this node; the timeline starts from it every frame. */
    var authoredEnabled: Boolean = spec.enabled
    var authoredTransform: VfxTransform = spec.transform

    var enabled: Boolean = spec.enabled
    var transform: VfxTransform = spec.transform

    /**
     * What the timeline writes into the values of this node. Only the properties that actually have
     * a track get one.
     */
    private val drives: Map<VfxProperty, VfxDrive> = driven.mapValues { (_, channels) -> VfxDrive(channels) }

    fun drive(property: VfxProperty): VfxDrive? = drives[property]

    /**
     * What a value read once for the whole node sees: the effect clock as its lifetime, the handle
     * data and the distance to the camera. Particle inputs read as zero.
     */
    val context = VfxEvalContext()

    /** The emitter this node draws the particles of, when it is a renderer directly under one. */
    val particleSource: VfxEmitter? = if (spec is VfxParticleRendererSpec) parent?.emitter else null

    val behavior: VfxNodeBehavior? = VfxNodeRuntimes.create(this)

    val emitter: VfxEmitter? get() = behavior as? VfxEmitter

    val children: List<VfxNodeRuntime> = spec.children.mapIndexed { index, child ->
        VfxNodeRuntime(
            child,
            this,
            instance,
            expressions,
            seed + index * 977 + 13,
            instance.drivenProperties(child.id),
        )
    }

    /** Whether this node and every node above it is on. */
    val isActive: Boolean get() = enabled && (parent?.isActive ?: true)

    fun place(root: VfxFrame) {
        frame.setCombined(parent?.frame ?: root, transform)
        behavior?.place()
        children.forEach { it.place(root) }
    }

    fun walk(): List<VfxNodeRuntime> = buildList {
        add(this@VfxNodeRuntime)
        children.forEach { addAll(it.walk()) }
    }

    fun reset() {
        enabled = authoredEnabled
        transform = authoredTransform
        children.forEach { it.reset() }
    }

    /** Refreshes [context] for this frame. */
    fun fillContext() {
        val camera = instance.camera
        val dx = frame.position.x - camera.x
        val dy = frame.position.y - camera.y
        val dz = frame.position.z - camera.z
        val duration = instance.effect.timeline.duration.coerceAtLeast(MIN_DURATION)
        val local = instance.timelineTime

        context.age = local
        context.lifetime = duration
        context.progress = (local / duration).coerceIn(0f, 1f)
        context.emitterAge = instance.time
        context.effectTime = instance.time
        context.partialTick = instance.partialTick
        context.gameTime = instance.gameTime
        context.data = instance.data
        context.cameraDistance = sqrt(dx * dx + dy * dy + dz * dz)
    }

    private companion object {
        const val MIN_DURATION = 0.01f
    }
}

/**
 * What a node of some kind does while its effect plays.
 *
 * Nodes are updated parents first, so a renderer under an emitter sees the particles of this step.
 */
interface VfxNodeBehavior {
    /** The node frame has been placed for this frame. */
    fun place() = Unit

    fun update(dt: Float) = Unit

    /** Back to the first moment of the effect. */
    fun restart() = Unit

    /** The effect is stopping; [immediate] clears what is left at once. */
    fun stop(immediate: Boolean) = Unit

    /** Whether nothing of this node is left on screen. */
    val isIdle: Boolean get() = true

    /** Adds what the node draws; [placement] maps effect space to the space of the view. */
    fun collect(into: VfxDrawList, placement: Matrix4f) = Unit

    /**
     * The effect is drawn this frame, held or not: for what a node does once a frame besides drawing, as a sound
     * keeping time with it. It may come more than once a frame, from several passes.
     */
    fun frame() = Unit
}

/** Makes the behavior of one node. Returning null leaves the node as a plain frame. */
fun interface VfxNodeFactory {
    fun create(node: VfxNodeRuntime): VfxNodeBehavior?
}

/**
 * What each kind of node does at runtime, keyed by the same id the spec is registered under.
 */
object VfxNodeRuntimes {
    val point = ExtensionPoints.create<VfxNodeFactory>("hollowengine:vfx/node_runtimes".rl)

    init {
        register("hollowengine:vfx/emitter") { VfxEmitter(it.spec as VfxEmitterSpec, it) }
        register("hollowengine:vfx/plane") { VfxPlaneNode(it.spec as VfxPlaneSpec, it) }
        register("hollowengine:vfx/cube") { VfxMeshNode(it.spec as VfxMeshSpec, it) }
        register("hollowengine:vfx/sphere") { VfxMeshNode(it.spec as VfxMeshSpec, it) }
        register("hollowengine:vfx/cylinder") { VfxMeshNode(it.spec as VfxMeshSpec, it) }
        register("hollowengine:vfx/model") { VfxModelNode(it.spec as VfxModelSpec, it) }
        register("hollowengine:vfx/trail") { VfxTrailRenderer(it.spec as VfxTrailSpec, it) }
        register("hollowengine:vfx/beam") { VfxBeamRenderer(it.spec as VfxBeamSpec, it) }
        register("hollowengine:vfx/post_effect") { VfxPostEffectNode(it.spec as VfxPostEffectSpec, it) }
        register("hollowengine:vfx/sky") { VfxSkyNode(it.spec as VfxSkySpec, it) }
        register("hollowengine:vfx/camera_shake") { VfxCameraShake(it.spec as VfxCameraShakeSpec, it) }
        register("hollowengine:vfx/sound") { VfxSoundNode(it.spec as VfxSoundSpec, it) }
        register("hollowengine:vfx/decal") { VfxDecalNode(it.spec as VfxDecalSpec, it) }
    }

    fun register(typeId: String, factory: VfxNodeFactory): ExtensionHandle = point.register(typeId.rl, factory)

    fun create(node: VfxNodeRuntime): VfxNodeBehavior? {
        val type = VfxNodeTypes.of(node.spec) ?: return null
        return point.find(type.key)?.create(node)
    }
}
