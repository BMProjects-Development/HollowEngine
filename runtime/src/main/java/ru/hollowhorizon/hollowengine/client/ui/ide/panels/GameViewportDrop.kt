package ru.hollowhorizon.hollowengine.client.ui.ide.panels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.editor.*
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.ui.UiCanvasDrawScope
import ru.hollowhorizon.hollowengine.client.ui.UiDragItem
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileDrag
import ru.hollowhorizon.hollowengine.client.ui.ide.projectPathReference
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.common.colliders.EntityColliders
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectKind
import ru.hollowhorizon.hollowengine.common.utils.PlayerPermissions
import ru.hollowhorizon.hollowengine.common.vfx.VfxFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Models and effects dragged from the project or the asset manager onto the game panel become world objects. */
internal object GameViewportDrop {
    const val TARGET_ID = "game-viewport-drop"

    /** How far the ray looks for something to stand the object on. */
    private const val REACH = 128.0

    /** Where the object goes when the ray meets nothing: in the air, this far along it. */
    private const val MISS_DISTANCE = 8.0

    private const val MARKER_RADIUS = 0.4
    private const val MARKER_SEGMENTS = 32

    /** Where the dragged file would land, while it is over the panel. */
    var preview by mutableStateOf<Vec3?>(null)
        private set

    /** What [item] would place, with the resource it shows; null when it is nothing a world object can show. */
    fun placement(item: UiDragItem): Pair<WorldObjectKind, String>? {
        val file = item.payload as? HollowIdeFileDrag ?: return null
        if (file.isDirectory || !file.path.startsWith("assets/")) return null
        val reference = projectPathReference(file.path)
        return when {
            reference.endsWith(VfxFormat.EXTENSION) -> WorldObjectKind.VFX to reference
            HollowModelManager.supports(reference) -> WorldObjectKind.MODEL to reference
            else -> null
        }
    }

    fun accepts(item: UiDragItem): Boolean =
        placement(item) != null && Minecraft.getInstance().player?.hasPermissions(PlayerPermissions.GAMEMASTER) == true

    fun hover(panel: UiRect?, x: Float, y: Float) {
        preview = panel?.let { pointAt(it, x, y) }
    }

    fun drop(item: UiDragItem, panel: UiRect?, x: Float, y: Float): Boolean {
        preview = null
        val (kind, asset) = placement(item) ?: return false
        val at = panel?.let { pointAt(it, x, y) } ?: return false
        WorldObjectEditing.spawn(kind, asset, at.x, at.y, at.z)
        return true
    }

    fun clear() {
        preview = null
    }

    /** The world point under ([x], [y]) of the IDE surface, through the game image drawn in [image]. */
    private fun pointAt(image: UiRect, x: Float, y: Float): Vec3? {
        if (image.width <= 0f || image.height <= 0f) return null
        val projector = WorldToScreenProjector
        val ray = projector.screenRay(
            (x - image.x) / image.width * projector.width,
            (y - image.y) / image.height * projector.height,
        ) ?: return null
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return null
        val player = minecraft.player ?: return null

        val start = ray.origin
        val end = start.add(ray.direction.scale(REACH))
        val block = level.clip(ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
        val blockPoint = block.location.takeIf { block.type != HitResult.Type.MISS }
        val blockDistance = blockPoint?.distanceToSqr(start) ?: (REACH * REACH)
        val search = AABB(start, blockPoint ?: end).inflate(1.0)
        val collider = EntityColliders.pick(
            level, player, start, blockPoint ?: end, search, { it !is Player }, blockDistance, null
        ) { true }
        return collider?.location ?: blockPoint ?: start.add(ray.direction.scale(MISS_DISTANCE))
    }

    /** A ring on the ground where the object will stand, drawn over the game image laid out in [image]. */
    fun drawMarker(scope: UiCanvasDrawScope, image: UiRect, point: Vec3) {
        val projector = WorldToScreenProjector
        val ring = (0 until MARKER_SEGMENTS).map { index ->
            val angle = index * 2.0 * PI / MARKER_SEGMENTS
            val screen =
                projector.project(point.x + cos(angle) * MARKER_RADIUS, point.y, point.z + sin(angle) * MARKER_RADIUS)
            if (!screen.onScreen) return
            Pt(image.x + screen.x / projector.width * image.width, image.y + screen.y / projector.height * image.height)
        }
        GizmoRenderer.strokeLine(scope, ring, GizmoColors.BOUNDS_ACTIVE, 1.6f, closed = true)
    }
}
