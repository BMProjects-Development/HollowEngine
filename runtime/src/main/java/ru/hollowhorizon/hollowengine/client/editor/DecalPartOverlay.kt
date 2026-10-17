package ru.hollowhorizon.hollowengine.client.editor

import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.client.decals.*
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.ui.UiCanvasDrawScope
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.ide.WorldObjectParts
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix4f
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderLevelStageEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderStage

/**
 * How the decal picked in the scene is cast, shown in the world while it is edited: the surfaces it lands on lit up,
 * and over the frame the volume it reaches, the face it is cast from with the top of its picture, and its direction.
 */
@ClientOnly
internal object DecalPartOverlay {
    /** Hands the decal over as where it lands, before the decals of the frame are drawn. */
    @SubscribeEvent
    fun onRenderLevel(event: RenderLevelStageEvent) {
        if (event.stage != RenderStage.AFTER_ENTITIES) return
        val part = WorldObjectParts.selectedPart() as? AttachmentGizmo ?: return
        val decal = part.decal ?: return
        val camera = event.camera.position
        val world = part.worldMatrix(event.partialTick).asMatrix4f()
        val placement = Matrix4f(world).setTranslation(
            (world.m30() - camera.x).toFloat(),
            (world.m31() - camera.y).toFloat(),
            (world.m32() - camera.z).toFloat(),
        )
        Decals.submit(DecalDraw(decal, placement, overlay = true))
    }

    /** Draws the guide of the decal [part] is over the frame, in the space of [WorldToScreenProjector]. */
    fun drawGuide(scope: UiCanvasDrawScope, part: AttachmentGizmo) {
        val decal = part.decal ?: return
        val placement = part.worldMatrix(TickHandler.partialTick).asMatrix4f()
        val extent = DecalExtents.of(decal, placement, DecalGround.world(Vec3.ZERO))
        val shape = Matrix4f(placement).mul(DecalShapes.unitCube(decal, extent))
        DecalShapes.guide(shape) { start, end, kind ->
            val from = screenOf(start) ?: return@guide
            val to = screenOf(end) ?: return@guide
            GizmoRenderer.strokeLine(scope, listOf(from, to), colorOf(kind), if (kind == DecalGuidePart.VOLUME) 1.2f else 1.8f)
        }
    }

    private fun screenOf(point: Vector3f): Pt? {
        val screen = WorldToScreenProjector.project(point.x.toDouble(), point.y.toDouble(), point.z.toDouble())
        return if (screen.onScreen) Pt(screen.x, screen.y) else null
    }

    private fun colorOf(kind: DecalGuidePart): UiColor = when (kind) {
        DecalGuidePart.VOLUME -> VolumeColor
        DecalGuidePart.SOURCE -> SourceColor
        DecalGuidePart.DIRECTION -> DirectionColor
    }

    private val VolumeColor = UiColor(1f, 0.64f, 0.2f, 0.8f)
    private val SourceColor = UiColor(1f, 0.88f, 0.54f, 1f)
    private val DirectionColor = UiColor(0.5f, 0.83f, 1f, 1f)
}
