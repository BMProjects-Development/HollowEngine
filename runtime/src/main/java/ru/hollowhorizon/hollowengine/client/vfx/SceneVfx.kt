package ru.hollowhorizon.hollowengine.client.vfx

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Matrix4dc
import org.joml.Matrix4f
import org.joml.Matrix4fc
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorExpressionEvaluator
import ru.hollowhorizon.hollowengine.client.render.RenderManager
import ru.hollowhorizon.hollowengine.client.scene.ClientSceneContext
import ru.hollowhorizon.hollowengine.client.scene.SceneComponentKey
import ru.hollowhorizon.hollowengine.client.scene.SceneDrawSystem
import ru.hollowhorizon.hollowengine.client.utils.math.copyTo
import ru.hollowhorizon.hollowengine.common.scene.ComponentActivity
import ru.hollowhorizon.hollowengine.common.vfx.VfxComponent
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect

internal val ClientSceneContext.vfx: SceneVfx?
    get() = systemFor(VfxComponent::class) as? SceneVfx

/** Owns playback for all VFX bindings in a scene. */
internal class SceneVfx : SceneDrawSystem {
    private val effects = HashMap<SceneComponentKey, VfxBoundEffect>()
    private val active = LinkedHashSet<VfxBoundEffect>()

    override val hasDraws: Boolean get() = active.isNotEmpty()
    val needsAnimationVariables: Boolean get() = active.any { it.needsAnimationVariables }

    override fun onStateChanged(binding: SceneComponentKey, state: ComponentActivity) {
        when (state) {
            ComponentActivity.ACTIVE -> active.add(effects.getOrPut(binding) { VfxBoundEffect(binding) })
            ComponentActivity.SUSPENDED -> effects[binding]?.let {
                active.remove(it)
                it.pause()
            }

            ComponentActivity.REMOVED -> effects.remove(binding)?.let { active.remove(it) }
        }
    }

    fun updateWorld(sceneToWorld: Matrix4dc, camera: Vec3, animation: AnimatorEvaluationContext?) {
        for (effect in active) effect.updateWorld(sceneToWorld, camera, animation)
    }

    fun updatePreview(seconds: Float?, emittingAt: ((bone: String?, id: String, time: Float) -> Boolean)?) {
        for (effect in active) effect.updatePreview(seconds, emittingAt)
    }

    override fun collect(sceneToView: Matrix4fc, light: Int) {
        for (effect in active) effect.collect(sceneToView)
    }
}

private class VfxBoundEffect(private val key: SceneComponentKey) {
    private val node get() = key.owner
    private val value get() = key.value as? VfxComponent
    private var asset = ""
    private var own: VfxEffect? = null
    private val local = Matrix4f()
    private var worldPlacement: Matrix4d? = null

    private var lastGameTime = Float.NaN
    private var simulationFrame = Long.MIN_VALUE

    private var binding: VfxBoneBinding? = null

    private var emitting = true
    private var conditionMet = true

    val needsAnimationVariables: Boolean
        get() = node.modelVisible && value?.activeWhile?.source?.isNotBlank() == true

    fun pause() {
        lastGameTime = Float.NaN
        simulationFrame = Long.MIN_VALUE
    }

    /** Advance once even if the level prepares several views of the same scene in a frame. */
    fun updateWorld(sceneToWorld: Matrix4dc, camera: Vec3, animation: AnimatorEvaluationContext?) {
        val spec = playableValue() ?: return pause()
        val frame = TickHandler.renderFrame
        if (simulationFrame == frame) return
        simulationFrame = frame

        use(spec.effect, spec.ownEffect)
        val condition = spec.activeWhile.source
        if (condition.isBlank()) conditionMet = true
        else if (animation != null) {
            conditionMet = animation.entity == null || AnimatorExpressionEvaluator.condition(condition, animation)
        }
        emitting = spec.emitting && conditionMet
        val placement = worldPlacement ?: Matrix4d().also { worldPlacement = it }
        placement.set(sceneToWorld).mul(node.worldTransform.copyTo(local))
        val origin = Vec3(placement.m30(), placement.m31(), placement.m32())
        advance(camera, origin, null, TickHandler.gameTime)
    }

    fun updatePreview(seconds: Float?, emittingAt: ((bone: String?, id: String, time: Float) -> Boolean)?) {
        val spec = playableValue() ?: return pause()
        use(spec.effect, spec.ownEffect)
        emitting = spec.emitting
        node.worldTransform.copyTo(local)
        val origin = Vec3(local.m30().toDouble(), local.m31().toDouble(), local.m32().toDouble())
        if (seconds == null) {
            val now = (System.nanoTime() - PreviewEpoch) / NANOS_PER_TICK
            advance(Vec3.ZERO, origin, PreviewFloor, now)
        } else {
            val bone = node.modelSource?.let { node.poseName }
            seek(origin, PreviewFloor, seconds) { at -> emittingAt?.invoke(bone, spec.id, at) ?: spec.emitting }
        }
    }

    fun collect(sceneToView: Matrix4fc) {
        val playing = binding ?: return
        val spec = playableValue() ?: return
        if (spec.effect != asset || spec.ownEffect != own) return
        val placement = SceneVfxBindings.submit(playing) ?: return
        node.worldTransform.copyTo(placement).mulLocal(sceneToView)
    }

    private fun playableValue() = value?.takeIf {
        node.modelVisible && it.autoPlay && (it.effect.isNotBlank() || it.ownEffect != null)
    }

    private fun advance(
        cameraPosition: Vec3,
        origin: Vec3,
        environment: VfxEnvironment?,
        now: Float,
    ) {
        val playing = binding?.instance ?: create(environment) ?: return
        val dt = when {
            lastGameTime.isNaN() -> 0f
            else -> ((now - lastGameTime) / TICKS_PER_SECOND).coerceIn(0f, MAX_STEP_SECONDS)
        }
        lastGameTime = now

        val partialTick = if (environment == null) TickHandler.partialTick else now.mod(1f)
        place(playing, cameraPosition, origin, now, partialTick)
        playing.emit(emitting)
        playing.update(dt)
        playing.updateFrame()
    }

    /** Seconds into the clock [seek] is given that the effect has been played to. */
    private var played = 0f

    /**
     * Puts the effect where it is [seconds] into a clock it does not keep, as a preview does that follows a
     * timeline; see [VfxInstance.seek]. [emittingAt] says when on that clock the effect makes particles.
     */
    private fun seek(origin: Vec3, environment: VfxEnvironment, seconds: Float, emittingAt: (Float) -> Boolean) {
        val playing = binding?.instance ?: create(environment)?.also { played = 0f } ?: return
        val now = seconds * TICKS_PER_SECOND
        place(playing, Vec3.ZERO, origin, now, now.mod(1f))
        playing.seek(played, seconds, emittingAt = emittingAt) { dt ->
            playing.budget = VfxBudget.share(1)
            playing.update(dt)
        }
        played = seconds.coerceAtLeast(0f)
        playing.updateFrame()
    }

    /** Supplies the origin and camera in the same coordinate space. */
    private fun place(playing: VfxInstance, cameraPosition: Vec3, origin: Vec3, now: Float, partialTick: Float) {
        playing.moveTo(origin)
        playing.setCamera(cameraPosition)
        playing.partialTick = partialTick
        playing.gameTime = now
        playing.budget = VfxBudget.share(1)
    }

    /**
     * Plays [asset], or [own] in its place, from now on. A change that only moves or switches nodes keeps the
     * running effect; any other starts it over.
     */
    private fun use(asset: String, own: VfxEffect?) {
        if (asset == this.asset && own == this.own) return
        val playing = binding?.instance
        val next = own ?: VfxAssets[asset]
        val keeps = asset == this.asset && playing != null && next != null && playing.adoptPlacement(next)
        this.asset = asset
        this.own = own
        if (!keeps) binding = null
    }

    /** Starts the effect in [environment], or in the world when none is given. */
    private fun create(environment: VfxEnvironment?): VfxInstance? {
        val surroundings = environment ?: VfxWorldEnvironment(Minecraft.getInstance().level ?: return null)
        val effect = own ?: VfxAssets[asset] ?: return null
        return VfxInstance(effect, asset, surroundings).also { binding = VfxBoneBinding(it, Matrix4f()) }
    }

    private companion object {
        const val TICKS_PER_SECOND = 20f
        const val MAX_STEP_SECONDS = 0.25f
    }
}

/** One placed effect, already advanced, with the matrix that places it in front of the camera. */
class VfxBoneBinding(val instance: VfxInstance, val placement: Matrix4f)

/**
 * The effects carried by bones and entities, drawn this frame.
 */
internal object SceneVfxBindings {
    private val submitted = LinkedHashSet<VfxBoneBinding>()
    private val bindings = ArrayList<VfxBoneBinding>()

    private class Capture(val draws: MutableList<VfxBoneBinding>) {
        val submitted = HashSet<VfxBoneBinding>()
    }

    private var captured: Capture? = null

    /** Returns the matrix for this submission, or null when this view already has the effect. */
    fun submit(source: VfxBoneBinding): Matrix4f? {
        val into = captured
        if (into != null) {
            if (!into.submitted.add(source)) return null
            val placement = Matrix4f()
            into.draws += VfxBoneBinding(source.instance, placement)
            return placement
        }
        return source.placement.takeIf {
            RenderManager.isWorldPass && !RenderManager.isShadowPass && submitted.add(
                source
            )
        }
    }

    /** Runs [draw], keeping the effects submitted meanwhile in [into], for a preview that draws them itself. */
    fun <T> capturing(into: MutableList<VfxBoneBinding>, draw: () -> T): T {
        val previous = captured
        captured = Capture(into)
        try {
            return draw()
        } finally {
            captured = previous
        }
    }

    fun drain(): List<VfxBoneBinding> {
        bindings.clear()
        bindings.addAll(submitted)
        submitted.clear()
        return bindings
    }

    internal fun clear() = submitted.clear()
}

/** What the attachment plays: its own copy of the effect, or the file; null while the file is missing. */
fun VfxComponent.played(): VfxEffect? = ownEffect ?: VfxAssets[effect]

/** The ground of an effect played in a preview: the floor the model stands on, at its origin. */
private val PreviewFloor = VfxPlaneEnvironment()

private const val NANOS_PER_TICK = 50_000_000f

/** When previews started counting, so their clock stays small enough for a float to keep its fractions. */
private val PreviewEpoch = System.nanoTime()
