package ru.hollowhorizon.hollowengine.client.ui.panels

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.client.models.internal.v2.Attachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RespecAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RigAttachmentFactories
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.render.RenderManager
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix4f
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.ui.UiDefinitionRegistry
import ru.hollowhorizon.hollowengine.common.ui.panels.UiPanelAttachmentSpec

/**
 * A panel hung on a bone: a quad facing along +Z of its place, drawn with the texture its [UiPanelInstance] keeps
 * drawn. In the world it also tells the panel where it stands, for the crosshair to find it; a preview without an
 * entity shows it with the player as the entity it reads.
 */
class UiPanelAttachment(
    spec: UiPanelAttachmentSpec,
    private val node: RuntimeNode,
    private val carrier: () -> Entity?,
) : Attachment(node), RespecAttachment {
    override var spec: UiPanelAttachmentSpec = spec
        private set

    private var panel: UiPanelInstance? = null

    override fun respec(spec: RigAttachmentSpec) {
        if (spec is UiPanelAttachmentSpec) this.spec = spec
    }

    /** The panel for what [spec] names now, made again when the name, the declaration or the entity changed. */
    private fun panel(): UiPanelInstance? {
        val id = ResourceLocation.tryParse(spec.panel) ?: return drop()
        val definition = UiDefinitionRegistry.panel(id) ?: return drop()
        val entity = carrier() ?: Minecraft.getInstance().player ?: return drop()
        val current = panel
        if (current != null && current.definition === definition && current.entity === entity) return current
        drop()
        return UiPanelInstance(definition, entity).also {
            panel = it
            UiPanels.add(it)
        }
    }

    private fun drop(): UiPanelInstance? {
        panel?.let {
            UiPanels.remove(it)
            it.close()
        }
        panel = null
        return null
    }

    override fun collectCommands(pipeline: RenderPipeline) {
        pipeline.addBatchedRenderable {
            if (!node.isVisible) return@addBatchedRenderable
            val shown = panel() ?: return@addBatchedRenderable
            val definition = shown.definition
            val width = spec.width
            val height = width * definition.height / definition.width
            val placement = Matrix4f(stack.last().pose())
                .mul(globalMatrix.asMatrix4f())
                .mul(spec.localTransform().matrixF.asMatrix4f())
                .scale(width, height, 1f)

            shown.seenFrame = TickHandler.renderFrame
            shown.reach = spec.reach
            shown.interactive = spec.interactive
            if (RenderManager.isWorldPass && !RenderManager.isShadowPass && carrier() != null) {
                val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
                shown.worldPlacement = Matrix4f().translation(camera.x.toFloat(), camera.y.toFloat(), camera.z.toFloat())
                    .mul(placement)
            }

            val brightness = if (spec.emissive) LightTexture.FULL_BRIGHT else light
            val pose = stack.last().copy()
            pose.pose().set(placement)
            val buffer = source.getBuffer(RenderType.entitySolid(shown.texture))
            fun corner(x: Float, y: Float, u: Float, v: Float) {
                buffer.addVertex(pose, x, y, 0f).setColor(-1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(brightness).setNormal(pose, 0f, 0f, 1f)
            }
            corner(-0.5f, -0.5f, 0f, 0f)
            corner(0.5f, -0.5f, 1f, 0f)
            corner(0.5f, 0.5f, 1f, 1f)
            corner(-0.5f, 0.5f, 0f, 1f)
        }
    }

    override fun close() {
        drop()
        super.close()
    }

    companion object {
        fun register() {
            RigAttachmentFactories.register("hollowengine:rig/ui_panel") { spec, context ->
                UiPanelAttachment(spec as UiPanelAttachmentSpec, context.node, context.carrier)
            }
        }
    }
}
