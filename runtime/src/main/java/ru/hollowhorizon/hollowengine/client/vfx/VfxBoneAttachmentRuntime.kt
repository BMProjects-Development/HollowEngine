package ru.hollowhorizon.hollowengine.client.vfx

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.client.models.internal.v2.Attachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RespecAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RigAttachmentFactories
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix4f
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxBoneAttachmentSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect

/**
 * One effect placed by whatever carries it, with the matrix captured while the carrier was drawn, and
 * advanced on game time.
 */
class VfxBoundEffect(asset: String, own: VfxEffect? = null) {
    private var asset: String = asset

    /** What plays instead of the file, when the carrier has its own copy of the effect. */
    private var own: VfxEffect? = own

    private var instance: VfxInstance? = null
    private var lastGameTime = Float.NaN
    private var lastFrame = Long.MIN_VALUE

    /** The effect's space to camera-relative space, as of the last time the carrier was drawn. */
    val placement = Matrix4f()

    /** True on the first call of a frame: a carrier is drawn by several passes, the effect only once. */
    fun claimFrame(): Boolean {
        val frame = TickHandler.renderFrame
        if (frame == lastFrame) return false
        lastFrame = frame
        return true
    }

    /** Advances the effect and places it at [local], camera-relative; null while its file is missing. */
    fun advance(cameraPosition: Vec3, local: Matrix4f): VfxBoneBinding? {
        val playing = instance ?: create() ?: return null

        val now = TickHandler.gameTime
        val dt = when {
            lastGameTime.isNaN() -> 0f
            else -> ((now - lastGameTime) / TICKS_PER_SECOND).coerceIn(0f, MAX_STEP_SECONDS)
        }
        lastGameTime = now

        val translation = local.getTranslation(Vector3f())
        playing.moveTo(
            Vec3(
                cameraPosition.x + translation.x,
                cameraPosition.y + translation.y,
                cameraPosition.z + translation.z,
            )
        )

        playing.setCamera(cameraPosition)
        playing.partialTick = TickHandler.partialTick
        playing.gameTime = now
        playing.budget = VfxBudget.share(1)
        playing.update(dt)

        return VfxBoneBinding(playing, local)
    }

    /**
     * Plays [asset], or [own] in its place, from now on. A change that only moves or switches nodes keeps the
     * running effect; any other starts it over.
     */
    fun use(asset: String, own: VfxEffect?) {
        if (asset == this.asset && own == this.own) return
        val playing = instance
        val next = own ?: VfxAssets[asset]
        val keeps = asset == this.asset && playing != null && next != null && playing.adoptPlacement(next)
        this.asset = asset
        this.own = own
        if (!keeps) instance = null
    }

    private fun create(): VfxInstance? {
        val level = Minecraft.getInstance().level ?: return null
        val effect = own ?: VfxAssets[asset] ?: return null
        return VfxInstance(effect, asset, VfxWorldEnvironment(level)).also { instance = it }
    }

    private companion object {
        const val TICKS_PER_SECOND = 20f
        const val MAX_STEP_SECONDS = 0.25f
    }
}

/** Something that hands the world renderer one placed effect a frame. */
fun interface VfxBindingSource {
    /** Advances the effect and says where it is; called once a frame by the world renderer. */
    fun update(cameraPosition: Vec3): VfxBoneBinding?
}

/**
 * An effect attached to bone.
 */
class VfxBoneAttachment(
    spec: VfxBoneAttachmentSpec,
    private val node: RuntimeNode,
) : Attachment(node), VfxBindingSource, RespecAttachment {
    override var spec: VfxBoneAttachmentSpec = spec
        private set

    private val effect = VfxBoundEffect(spec.effect, spec.ownEffect)

    override fun respec(spec: RigAttachmentSpec) {
        if (spec !is VfxBoneAttachmentSpec) return
        this.spec = spec
        effect.use(spec.effect, spec.ownEffect)
    }

    override fun collectCommands(pipeline: RenderPipeline) {
        if (!spec.autoPlay || spec.effect.isBlank() && spec.ownEffect == null) return

        pipeline.addBatchedRenderable {
            if (!node.isVisible) return@addBatchedRenderable
            if (!effect.claimFrame()) return@addBatchedRenderable

            effect.placement.set(stack.last().pose()).mul(globalMatrix.asMatrix4f())
            VfxBoneBindings.submit(this@VfxBoneAttachment)
        }
    }

    override fun update(cameraPosition: Vec3): VfxBoneBinding? {
        val local = Matrix4f(effect.placement)
        applyOffset(local)
        return effect.advance(cameraPosition, local)
    }

    /** The offset the author gave the attachment, on top of the bone. */
    private fun applyOffset(matrix: Matrix4f) {
        matrix.mul(spec.localTransform().matrixF.asMatrix4f())
    }
}

/** One placed effect, already advanced, with the matrix that places it in front of the camera. */
class VfxBoneBinding(val instance: VfxInstance, val placement: Matrix4f)

/**
 * The effects carried by bones and entities, drawn this frame.
 */
object VfxBoneBindings {
    private val submitted = ArrayList<VfxBindingSource>()
    private val bindings = ArrayList<VfxBoneBinding>()

    fun submit(source: VfxBindingSource) {
        submitted += source
    }

    fun drain(cameraPosition: Vec3): List<VfxBoneBinding> {
        bindings.clear()
        submitted.forEach { source -> source.update(cameraPosition)?.let(bindings::add) }
        submitted.clear()
        return bindings
    }

    fun register() {
        RigAttachmentFactories.register("hollowengine:rig/vfx") { spec, context ->
            VfxBoneAttachment(spec as VfxBoneAttachmentSpec, context.node)
        }
    }
}

/** What the attachment plays: its own copy of the effect, or the file; null while the file is missing. */
fun VfxBoneAttachmentSpec.played(): VfxEffect? = ownEffect ?: VfxAssets[effect]
