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
import ru.hollowhorizon.hollowengine.client.scene.ClientComponent
import ru.hollowhorizon.hollowengine.client.scene.ComponentUpdate
import ru.hollowhorizon.hollowengine.client.scene.RenderComponent
import ru.hollowhorizon.hollowengine.client.utils.math.copyTo
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.vfx.VfxComponent
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect

/**
 * Playback state for one attached effect.
 */
private class VfxBoundEffect(private var asset: String, private var own: VfxEffect? = null) {

    private var lastGameTime = Float.NaN
    private var simulationFrame = Long.MIN_VALUE

    var binding: VfxBoneBinding? = null
        private set

    /** Whether the effect makes particles; otherwise it is held, and what it made lives out its lifetime. */
    var emitting: Boolean = true

    fun pause() {
        lastGameTime = Float.NaN
        simulationFrame = Long.MIN_VALUE
    }

    /** Advance once even if the level prepares several views of the same scene in a frame. */
    fun advanceWorld(cameraPosition: Vec3, transform: Matrix4dc) {
        val frame = TickHandler.renderFrame
        if (simulationFrame == frame) return
        simulationFrame = frame
        advance(cameraPosition, Vec3(transform.m30(), transform.m31(), transform.m32()), null, TickHandler.gameTime)
    }

    fun advance(
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
    fun seek(origin: Vec3, environment: VfxEnvironment, seconds: Float, emittingAt: (Float) -> Boolean) {
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
    fun use(asset: String, own: VfxEffect?) {
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

/** An effect attached to a spatial object. */
internal class SceneVfx(
    private val node: SceneObject,
    private var spec: VfxComponent,
) : ClientComponent, RenderComponent {
    private var enabled = false

    private val effect = VfxBoundEffect(spec.effect, spec.ownEffect).also { it.emitting = spec.emitting }
    private val local = Matrix4f()
    private var worldPlacement: Matrix4d? = null

    /** Whether the condition of the attachment held at the last frame; see [VfxComponent.activeWhile]. */
    private var active = true

    override fun activate() { enabled = true }

    override fun suspend() {
        enabled = false
        effect.pause()
    }

    override fun change(value: Any) {
        spec = value as VfxComponent
        effect.use(spec.effect, spec.ownEffect)
        if (spec.activeWhile.source.isBlank()) active = true
        effect.emitting = spec.emitting && active
        if (!spec.autoPlay) effect.pause()
    }

    override val requiresAnimationVariables: Boolean
        get() = enabled && node.modelVisible && spec.activeWhile.source.isNotBlank()

    private fun evaluate(context: AnimatorEvaluationContext) {
        active = context.entity == null || AnimatorExpressionEvaluator.condition(spec.activeWhile.source, context)
        effect.emitting = spec.emitting && active
    }

    override fun update(frame: ComponentUpdate) {
        if (!isPlaying()) {
            effect.pause()
            return
        }
        val transform = node.worldTransform.copyTo(local)
        when (frame) {
            is ComponentUpdate.World -> {
                if (requiresAnimationVariables) frame.animation?.let(::evaluate)
                val placement = worldPlacement ?: Matrix4d().also { worldPlacement = it }
                effect.advanceWorld(frame.camera, placement.set(frame.sceneToWorld).mul(transform))
            }
            is ComponentUpdate.Preview -> {
                effect.emitting = spec.emitting
                val origin = Vec3(transform.m30().toDouble(), transform.m31().toDouble(), transform.m32().toDouble())
                val seconds = frame.effectTime
                if (seconds == null) {
                    val now = (System.nanoTime() - PreviewEpoch) / NANOS_PER_TICK
                    effect.advance(Vec3.ZERO, origin, PreviewFloor, now)
                } else effect.seek(origin, PreviewFloor, seconds) { at ->
                    frame.effectEmitting?.invoke(node.modelSource?.let { node.poseName }, spec.id, at) ?: spec.emitting
                }
            }
        }
    }

    override fun collect(sceneToView: Matrix4fc) {
        if (!isPlaying()) return
        val binding = effect.binding ?: return
        if (SceneVfxBindings.submit(binding)) {
            node.worldTransform.copyTo(binding.placement).mulLocal(sceneToView)
        }
    }

    private fun isPlaying() = enabled && node.modelVisible && spec.autoPlay && (spec.effect.isNotBlank() || spec.ownEffect != null)
}

/** One placed effect, already advanced, with the matrix that places it in front of the camera. */
class VfxBoneBinding(val instance: VfxInstance, val placement: Matrix4f)

/**
 * The effects carried by bones and entities, drawn this frame.
 */
internal object SceneVfxBindings {
    private val submitted = LinkedHashSet<VfxBoneBinding>()
    private val bindings = ArrayList<VfxBoneBinding>()

    /** Where effects go while a preview draws a model, instead of to the world. */
    private var captured: MutableList<VfxBoneBinding>? = null

    fun submit(source: VfxBoneBinding): Boolean {
        val into = captured
        return if (into != null) {
            if (source in into) false else into.add(source)
        } else RenderManager.isWorldPass && !RenderManager.isShadowPass && submitted.add(source)
    }

    /** Runs [draw], keeping the effects submitted meanwhile in [into], for a preview that draws them itself. */
    fun <T> capturing(into: MutableList<VfxBoneBinding>, draw: () -> T): T {
        val previous = captured
        captured = into
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
