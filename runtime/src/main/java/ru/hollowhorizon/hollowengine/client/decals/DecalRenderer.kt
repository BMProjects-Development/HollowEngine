package ru.hollowhorizon.hollowengine.client.decals

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.LightTexture
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.render.RenderManager
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxMaterialStates
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxShaders
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxView
import ru.hollowhorizon.hollowengine.client.vfx.render.drawImmediate
import ru.hollowhorizon.hollowengine.common.decals.DecalBlend
import ru.hollowhorizon.hollowengine.common.decals.DecalProjection
import ru.hollowhorizon.hollowengine.common.decals.DecalSpec
import kotlin.math.cos

/**
 * One decal to draw this frame: what it is, where it stands in the space of the view it is drawn with, the color
 * it is tinted with on top of its own, alpha included, and the packed light where it is. [key] names the attachment it
 * came from as `bone/id`, the bone blank on the model itself, for an editor to find it by; an [overlay] shows where
 * the decal lands instead of its picture.
 */
class DecalDraw(
    val spec: DecalSpec,
    val placement: Matrix4f,
    val light: Int = LightTexture.FULL_BRIGHT,
    val tint: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    val key: String? = null,
    val overlay: Boolean = false,
    val carried: Boolean = true,
) {
    /** The same decal shown as where it lands. */
    fun asOverlay() = DecalDraw(spec, placement, light, tint, key, overlay = true, carried = carried)
}

/**
 * The decals hung on models this frame, handed over as the models are drawn and drawn after them, with the effects.
 * A preview that draws models into a panel takes them for itself.
 */
object Decals {
    private val submitted = ArrayList<DecalDraw>()
    private var captured: MutableList<DecalDraw>? = null

    /** Takes [draw] for the frame; a model drawn into an interface outside a preview that takes it hands in nothing. */
    fun submit(draw: DecalDraw) {
        val into = captured ?: submitted.takeIf { RenderManager.isWorldPass } ?: return
        into += draw
    }

    /** Runs [draw], keeping the decals submitted meanwhile in [into]. */
    fun <T> capturing(into: MutableList<DecalDraw>, draw: () -> T): T {
        val previous = captured
        captured = into
        try {
            return draw()
        } finally {
            captured = previous
        }
    }

    /** The decals of this frame, taken away so the next starts empty. */
    fun drain(): List<DecalDraw> {
        if (submitted.isEmpty()) return emptyList()
        return ArrayList(submitted).also { submitted.clear() }
    }
}

/**
 * Draws decals over what the bound framebuffer already shows. Each is the box or the cone it reaches, drawn by its
 * back faces with no depth test, so a camera inside it still sees it; the shader finds the surface under each
 * pixel in a copy of the depth and keeps the pixels that lie inside.
 */
object DecalRenderer {
    private const val SHADER = "hollowengine:decal/decal"

    private val viewport = IntArray(4)

    fun render(draws: List<DecalDraw>, view: VfxView) {
        if (draws.isEmpty()) return
        val shader = VfxShaders.get(SHADER, DefaultVertexFormat.POSITION) ?: return
        GL33.glGetIntegerv(GL33.GL_VIEWPORT, viewport)
        if (!DecalDepth.scene.capture(viewport)) return
        val inWorld = view.decalGround is DecalGround.World
        if (inWorld) DecalDepth.drawnFrame = TickHandler.renderFrame
        val blocks = inWorld && DecalDepth.blocksReady
        val toView = Matrix4f(view.projection).mul(view.modelView).invert()

        RenderSystem.disableDepthTest()
        RenderSystem.depthMask(false)
        RenderSystem.enableCull()
        try {
            draws.forEach { draw ->
                if (!draw.overlay && draw.spec.opacity * draw.tint[3] <= 0f) return@forEach
                val texture = if (draw.overlay) 0 else DecalTextures.of(draw.spec) ?: return@forEach
                val extent = DecalExtents.of(draw.spec, draw.placement, view.decalGround)
                val cube = Matrix4f(draw.placement).mul(DecalShapes.unitCube(draw.spec, extent))
                val inverse = Matrix4f(cube).invert()
                val up = draw.placement.transformDirection(Vector3f(0f, 1f, 0f)).normalize()
                val origin = draw.placement.getTranslation(Vector3f())
                val world = view.decalGround as? DecalGround.World
                val carrier = if (world != null && draw.carried) DecalCarrier.of(origin, world.camera) else null
                val mesh = cubeMesh(cube) ?: return@forEach

                GL33.glCullFace(if (cube.determinant() > 0f) GL33.GL_FRONT else GL33.GL_BACK)
                blend(if (draw.overlay) DecalBlend.NORMAL else draw.spec.blend)
                view.drawImmediate(mesh, shader) { bound ->
                    bound.setSampler("Sampler0", texture)
                    bound.setSampler("Sampler2", HollowModelManager.lightTexture.id)
                    bound.setSampler("SceneDepth", DecalDepth.scene.textureId)
                    bound.safeGetUniform("InvViewProjMat").set(toView)
                    bound.safeGetUniform("DecalInverse").set(inverse)
                    bound.safeGetUniform("DecalUp").set(up.x, up.y, up.z)
                    val color = draw.spec.color
                    val tint = draw.tint
                    bound.safeGetUniform("DecalColor").set(
                        color.x * tint[0], color.y * tint[1], color.z * tint[2], draw.spec.opacity * tint[3],
                    )
                    bound.safeGetUniform("AngleFade").set(cos(Math.toRadians(draw.spec.angleFade.toDouble())).toFloat())
                    val lit = !draw.spec.emissive && draw.light != LightTexture.FULL_BRIGHT
                    bound.safeGetUniform("LightUV").set(
                        if (lit) (draw.light and 0xFFFF).toFloat() else -1f,
                        if (lit) (draw.light ushr 16 and 0xFFFF).toFloat() else -1f,
                    )
                    bound.safeGetUniform("Multiply").set(if (!draw.overlay && draw.spec.blend == DecalBlend.MULTIPLY) 1f else 0f)
                    bound.safeGetUniform("Overlay").set(if (draw.overlay) 1f else 0f)
                    bound.safeGetUniform("ScreenSize").set(DecalDepth.scene.copyWidth.toFloat(), DecalDepth.scene.copyHeight.toFloat())
                    bound.safeGetUniform("Viewport").set(
                        viewport[0].toFloat(), viewport[1].toFloat(), viewport[2].toFloat(), viewport[3].toFloat(),
                    )
                    val projector = draw.spec.projection == DecalProjection.PROJECTOR
                    bound.safeGetUniform("CastFrom").set(origin.x, origin.y, origin.z, if (projector) 1f else 0f)
                    bound.setSampler("BlockDepth", if (blocks) DecalDepth.blocks.textureId else 0)
                    bound.safeGetUniform("Blocks").set(if (blocks) 1f else 0f)
                    bound.safeGetUniform("Carrier").set(if (carrier != null) 1f else 0f)
                    if (carrier != null) {
                        bound.safeGetUniform("CarrierMin").set(carrier.minX.toFloat(), carrier.minY.toFloat(), carrier.minZ.toFloat())
                        bound.safeGetUniform("CarrierMax").set(carrier.maxX.toFloat(), carrier.maxY.toFloat(), carrier.maxZ.toFloat())
                    }
                }
            }
        } finally {
            GL33.glCullFace(GL33.GL_BACK)
            VfxMaterialStates.restore()
        }
        DecalTextures.release()
    }

    /** The twelve triangles of the unit cube taken through [cube], each face wound outward before it. */
    private fun cubeMesh(cube: Matrix4f) = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION).let { builder ->
        val corners = Array(8) { index ->
            cube.transformProject(
                Vector3f(if (index and 1 != 0) 1f else -1f, if (index and 2 != 0) 1f else -1f, if (index and 4 != 0) 1f else -1f),
            )
        }
        Faces.forEach { (a, b, c, d) ->
            for (corner in intArrayOf(a, b, c, a, c, d)) corners[corner].let { builder.addVertex(it.x, it.y, it.z) }
        }
        builder.build()
    }

    private fun blend(blend: DecalBlend) {
        RenderSystem.enableBlend()
        RenderSystem.blendEquation(GL33.GL_FUNC_ADD)
        when (blend) {
            DecalBlend.NORMAL -> RenderSystem.defaultBlendFunc()
            DecalBlend.ADDITIVE -> RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE)
            DecalBlend.MULTIPLY -> RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO)
        }
    }

    /** The faces of the unit cube by its corners (bit 0 is x, 1 is y, 2 is z), counterclockwise seen from outside. */
    private val Faces = listOf(
        intArrayOf(0, 4, 6, 2), // -x
        intArrayOf(1, 3, 7, 5), // +x
        intArrayOf(0, 1, 5, 4), // -y
        intArrayOf(2, 6, 7, 3), // +y
        intArrayOf(0, 2, 3, 1), // -z
        intArrayOf(4, 5, 7, 6), // +z
    )
}

