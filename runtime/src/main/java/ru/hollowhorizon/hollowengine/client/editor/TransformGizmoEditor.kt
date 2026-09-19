package ru.hollowhorizon.hollowengine.client.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mojang.blaze3d.systems.RenderSystem
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import net.minecraft.world.entity.Entity
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.render.ResolvedNodeTransform
import ru.hollowhorizon.hollowengine.client.models.internal.v2.modelInstanceOrNull
import ru.hollowhorizon.hollowengine.client.render.buildNodeRenderBounds
import ru.hollowhorizon.hollowengine.client.render.resolveNodeTransform
import ru.hollowhorizon.hollowengine.client.render.worldTransformToComponent
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOverlay
import ru.hollowhorizon.hollowengine.client.ui.ide.hollowIdeModifierMask
import ru.hollowhorizon.hollowengine.client.ui.ide.hollowIdeOverlayPoint
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.style.UiShadow
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.math.rotateBy
import ru.hollowhorizon.hollowengine.common.config.HollowEngineConfig
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderLevelStageEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderStage
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderTickEvent
import ru.hollowhorizon.hollowengine.common.attachments.binding.*
import ru.hollowhorizon.hollowengine.common.attachments.components.*
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.Snapshot
import ru.hollowhorizon.hollowengine.common.utils.PlayerPermissions
import java.util.*
import kotlin.math.*

/**
 * The scene-node transform editor. The gizmo handles, bounds and light previews are projected
 * into screen space ([WorldToScreenProjector]) and stroked with anti-aliased SDF paths;
 * picking ([GizmoPicker]) and manipulation ([GizmoManipulator]) run in screen space too.
 */
@ClientOnly
object TransformGizmoEditor {

    internal const val MODEL_ICON = "hollowengine:textures/gui/icons/box.svg"
    internal const val TRANSFORM_ICON = "hollowengine:textures/gui/icons/world.svg"

    private val entries = linkedMapOf<GizmoEntryId, GizmoEntry>()

    private var hoveredKey: GizmoEntryId? = null
    private var draggingKey: GizmoEntryId? = null
    private var activeKey: GizmoEntryId? = null
    private var hoveredHandleId: GizmoHandleId? = null
    private var draggingHandleId: GizmoHandleId? = null
    private var currentDrag: GizmoDrag? = null
    private var isInitialized = false
    private var lastPhysX = 0f
    private var lastPhysY = 0f

    private var labelState by mutableStateOf<OverlayLabelState?>(null)
    private var contextMenuState by mutableStateOf<ContextMenuState?>(null)

    private var enabledValue by mutableStateOf(false)
    private var modesValue by mutableStateOf(setOf(GizmoEditMode.TRANSLATE))

    val isEnabled: Boolean get() = enabledValue

    /** The manipulators drawn together; never empty, the last one stays remembered while the gizmo is off. */
    val modes: Set<GizmoEditMode> get() = modesValue

    private val overlay: HollowUiWorldOverlay by lazy {
        HollowUiWorldOverlay(
            pointerOverride = {
                if (isEditorAvailable() && crosshairMode()) WorldToScreenProjector.screenCenter() else null
            },
            manageCursor = false,
        ).apply {
            setContent { GizmoOverlayContent() }
        }
    }

    fun toggleEnabled() = setEnabled(!isEnabled)

    fun setEnabled(enabled: Boolean) {
        ensureInitialized()
        if (enabledValue == enabled) return
        enabledValue = enabled
        HollowEngineConfig.gizmoEnabled = enabled
        if (!enabled) cancelInteraction()
    }

    fun isModeShown(mode: GizmoEditMode): Boolean = enabledValue && mode in modesValue

    /**
     * Shows or hides one manipulator. Turning one on also turns the gizmo on; turning off the last
     * one turns the gizmo off instead of leaving an empty gizmo, and keeps that mode for next time.
     */
    fun setModeShown(mode: GizmoEditMode, shown: Boolean) {
        ensureInitialized()
        when {
            shown && !enabledValue -> {
                setModes(setOf(mode))
                setEnabled(true)
            }

            shown -> setModes(modesValue + mode)
            !enabledValue || mode !in modesValue -> Unit
            modesValue.size == 1 -> setEnabled(false)
            else -> setModes(modesValue - mode)
        }
    }

    fun toggleMode(mode: GizmoEditMode) = setModeShown(mode, !isModeShown(mode))

    private fun setModes(modes: Set<GizmoEditMode>) {
        if (modes.isEmpty() || modesValue == modes) return
        modesValue = modes
        HollowEngineConfig.gizmoModes = GizmoEditMode.entries.filter(modes::contains).joinToString(",") { it.name }
        currentDrag = null
        draggingKey = null
        draggingHandleId = null
        hoveredHandleId = null
        labelState = null
    }

    private fun ensureInitialized() {
        if (isInitialized) return
        isInitialized = true
        enabledValue = HollowEngineConfig.gizmoEnabled
        modesValue = HollowEngineConfig.gizmoModes.split(',')
            .mapNotNull { name -> GizmoEditMode.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) } }
            .toSet()
            .ifEmpty { setOf(GizmoEditMode.TRANSLATE) }
    }

    @SubscribeEvent
    fun onRenderLevel(event: RenderLevelStageEvent) {
        when (event.stage) {
            RenderStage.AFTER_ENTITIES -> captureCamera(event)
            RenderStage.AFTER_LEVEL -> renderGizmo()
            else -> Unit
        }
    }

    private fun captureCamera(event: RenderLevelStageEvent) {
        val minecraft = Minecraft.getInstance()
        if (minecraft.player == null || minecraft.level == null) return
        val fov = minecraft.gameRenderer.getFov(event.camera, event.partialTick, true).toFloat()
        WorldToScreenProjector.capture(
            view = Matrix4f(RenderSystem.getModelViewMatrix()),
            projection = event.projectionMatrix,
            cameraPosition = event.camera.position,
            fovDegrees = fov,
        )
    }

    private fun renderGizmo() {
        ensureInitialized()
        if (!isEditorAvailable()) return
        syncVisibleEntries()
        if (crosshairMode()) {
            val drag = currentDrag
            val entry = draggingKey?.let(entries::get)
            if (drag != null && entry != null) {
                val (cx, cy) = WorldToScreenProjector.screenCenter()
                GizmoManipulator.update(drag, cx, cy, hollowIdeModifierMask())?.let { values ->
                    entry.working = values
                    applyFromGizmo(entry, values)
                    updateLabel(entry, drag)
                }
            }
            val (cx, cy) = WorldToScreenProjector.screenCenter()
            updateHover(cx, cy)
        }
        overlay.render()
    }

    @SubscribeEvent
    fun onApplyCursor(event: RenderTickEvent.Post) {
        val window = Minecraft.getInstance().window.window
        if (!isInitialized || !isEditorAvailable() || crosshairMode()) {
            UiCursorManager.release(window, this)
            return
        }
        val hovering = hoveredHandleId != null || hoveredKey != null || overlay.isMouseOver(lastPhysX, lastPhysY)
        UiCursorManager.claim(
            window = window,
            owner = this,
            shape = UiCursorShape.HAND.takeIf { hovering },
            priority = UiCursorManager.WorldPriority,
        )
    }

    fun handleMouseButton(physX: Float, physY: Float, button: Int, action: Int): Boolean {
        ensureInitialized()
        if (!isEditorAvailable()) return false
        if (!crosshairMode() && pointerOverIde(physX, physY) && action == GLFW.GLFW_PRESS) return false
        if (!crosshairMode() && overlay.handleMouseButton(physX, physY, button, action)) return true
        val (x, y) = pointerLogical(physX, physY)
        return when (action) {
            GLFW.GLFW_PRESS -> onPress(x, y, button)
            GLFW.GLFW_RELEASE -> onRelease(button)
            else -> false
        }
    }

    fun handleMouseMove(physX: Float, physY: Float): Boolean {
        ensureInitialized()
        lastPhysX = physX
        lastPhysY = physY
        if (!isEditorAvailable() || crosshairMode()) return false
        overlay.handleMouseMove(physX, physY)
        val (x, y) = pointerLogical(physX, physY)
        val drag = currentDrag
        if (drag == null && pointerOverIde(physX, physY)) {
            hoveredHandleId = null
            hoveredKey = null
            return false
        }
        if (drag != null) {
            val entry = draggingKey?.let(entries::get)
            val values = GizmoManipulator.update(drag, x, y, hollowIdeModifierMask())
            if (values != null && entry != null) {
                entry.working = values
                applyFromGizmo(entry, values)
                updateLabel(entry, drag)
            }
            return true
        }
        updateHover(x, y)
        return hoveredHandleId != null
    }

    fun handleMouseScroll(physX: Float, physY: Float, scrollX: Double, scrollY: Double): Boolean {
        if (!isEditorAvailable() || crosshairMode()) return false
        return overlay.handleMouseScroll(physX, physY, scrollX, scrollY)
    }

    fun handleKey(key: Int, scanCode: Int, action: Int, modifiers: Int): Boolean {
        if (!isEditorAvailable() || crosshairMode()) return false
        return overlay.handleKey(key, scanCode, action, modifiers)
    }

    fun handleChar(codePoint: Int, modifiers: Int): Boolean {
        if (!isEditorAvailable() || crosshairMode()) return false
        return overlay.handleChar(codePoint, modifiers)
    }

    fun shouldBlockScreenInput(physX: Float, physY: Float): Boolean {
        if (!isInitialized || !isEditorAvailable()) return false
        if (draggingKey != null) return true
        if (!crosshairMode()) {
            if (HollowIdeOverlay.isMouseOver(physX, physY) || pointerOverIde(physX, physY)) return false
            if (overlay.isMouseOver(physX, physY)) return true
        }
        val (x, y) = pointerLogical(physX, physY)
        return activeEntryHandleAt(x, y) != null || pickBounds(x, y) != null
    }

    private fun onPress(x: Float, y: Float, button: Int): Boolean {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && crosshairMode()) return false
        val handle = activeEntryHandleAt(x, y)
        if (handle != null) {
            val entry = activeKey?.let(entries::get) ?: return false
            when (button) {
                GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                    currentDrag = GizmoManipulator.begin(handle, entry.working ?: return false, x, y)
                    draggingKey = entry.entryId
                    draggingHandleId = handle.id
                    contextMenuState = null
                    return true
                }

                GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
                    openContextMenu(entry.entryId, x, y)
                    return true
                }
            }
        }

        val hit = pickBounds(x, y)
        return when {
            hit != null && button == GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                activeKey = hit; contextMenuState = null; true
            }

            hit != null && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
                activeKey = hit; openContextMenu(hit, x, y); true
            }

            button == GLFW.GLFW_MOUSE_BUTTON_LEFT -> {
                clearSelection(); false
            }

            button == GLFW.GLFW_MOUSE_BUTTON_RIGHT -> {
                contextMenuState = null; false
            }

            else -> false
        }
    }

    private fun onRelease(button: Int): Boolean {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || draggingKey == null) return false
        val entry = draggingKey?.let(entries::get)
        draggingKey = null
        draggingHandleId = null
        currentDrag = null
        labelState = null
        entry?.let { refreshFromRuntime(it) }
        return true
    }

    private fun updateHover(x: Float, y: Float) {
        hoveredHandleId = activeEntryHandleAt(x, y)?.id
        hoveredKey = if (hoveredHandleId != null) activeKey else pickBounds(x, y)
    }

    private fun activeEntryHandleAt(x: Float, y: Float): GizmoHandle? {
        val entry = activeKey?.let(entries::get)?.takeIf { it.visible } ?: return null
        val working = entry.working ?: return null
        val handles = GizmoGeometry.buildHandles(working.translation, working.rotation, modes)
        return GizmoPicker.pick(handles, x, y)
    }

    private fun openContextMenu(entryId: GizmoEntryId, x: Float, y: Float) {
        contextMenuState = ContextMenuState(entryId, UiRect(x, y, 0f, 0f))
    }

    private fun clearSelection() {
        activeKey = null
        hoveredKey = null
        hoveredHandleId = null
        contextMenuState = null
    }

    private fun cancelInteraction() {
        currentDrag = null
        draggingKey = null
        draggingHandleId = null
        hoveredKey = null
        hoveredHandleId = null
        activeKey = null
        labelState = null
        contextMenuState = null
    }

    @Composable
    private fun GizmoOverlayContent() {
        Box(
            id = "transform-gizmo-root",
            modifier = Modifier.style("hollowengine:ui/styles/widgets.hss")
                .size(100.percent, 100.percent)
                .drawBehind(key = "transform-gizmo-layer") { drawGizmoLayer(this) },
        ) {
            labelState?.let { label ->
                Text(
                    formatLabelValue(label.value),
                    modifier = Modifier.position((label.x - 36f).px, (label.y - 18f).px)
                        .padding(9.px, 5.px)
                        .background(UiColor(0.08f, 0.10f, 0.14f, 0.95f))
                        .border(1.px, UiColor(0.45f, 0.55f, 0.70f, 0.75f), 7f)
                        .shadow(
                            UiShadow(
                                offset = UiVec3(0f, 2f),
                                blur = 7f,
                                spread = 0f,
                                color = UiColor(0f, 0f, 0f, 0.55f)
                            )
                        )
                        .foreground(UiColor(0.94f, 0.96f, 1f)),
                )
            }

            contextMenuState?.let { menu ->
                if (entries[menu.entryId] != null) {
                    ContextMenu(
                        id = "transform-gizmo-context",
                        anchorBounds = menu.anchor,
                        items = contextMenuItems(menu.entryId),
                        onExpandedChange = { open -> if (!open) contextMenuState = null },
                    )
                }
            }
        }
    }

    private fun contextMenuItems(entryId: GizmoEntryId): List<UiDropdownItem> = listOf(
        UiDropdownItem("Reset Transform", icon = "hollowengine:textures/gui/icons/general.svg") {
            resetTransform(entryId)
        },
        UiDropdownItem("Copy Snapshot ID", icon = "hollowengine:textures/gui/icons/copy.svg") {
            Minecraft.getInstance().keyboardHandler.clipboard = entryId.snapshotId.toString()
            Minecraft.getInstance().player?.displayClientMessage(Component.literal("Snapshot id copied"), true)
            contextMenuState = null
        },
        UiDropdownItem("Clear Selection", icon = "hollowengine:textures/gui/icons/remove.svg") {
            clearSelection()
        },
    )

    private fun drawGizmoLayer(scope: UiCanvasDrawScope) {
        if (!isEditorAvailable()) return
        for ((entryId, entry) in entries) {
            if (!entry.visible) continue
            val boundsColor = when (entryId) {
                activeKey -> GizmoColors.BOUNDS_ACTIVE
                hoveredKey -> GizmoColors.BOUNDS_HOVER
                else -> GizmoColors.BOUNDS
            }
            entry.lastBounds?.let { bounds ->
                val width = boundsLineWidth(bounds)
                for (edge in GizmoGeometry.buildBoundsEdges(bounds)) strokeLine(scope, edge, boundsColor, width)
            }
        }

        val active = activeKey?.let(entries::get)?.takeIf { it.visible } ?: return
        val working = active.working ?: return

        val drag = currentDrag
        if (drag != null && drag.handleId.isRotation() && draggingKey == active.entryId) {
            drag.axis?.let { axis ->
                val perPixel = WorldToScreenProjector.worldPerPixel(drag.origin)
                GizmoGeometry.buildRotationSector(drag.origin, axis, drag.startAngle, drag.angle, perPixel)
                    ?.let { sector ->
                        fillPolygon(scope, sector, UiColor(1f, 0.85f, 0.32f, 0.25f))
                        strokeLine(scope, sector, UiColor(1f, 0.86f, 0.34f, 0.9f), 1.5f, closed = true)
                    }
            }
        }

        val handles = GizmoGeometry.buildHandles(working.translation, working.rotation, modes)
            .sortedByDescending { it.depth }
        for (handle in handles) {
            val highlighted =
                handle.id == draggingHandleId || (draggingHandleId == null && handle.id == hoveredHandleId)
            val base = if (highlighted) GizmoColors.highlighted(handle.color) else handle.color
            val emphasis = if (highlighted) 1f else handle.emphasis
            handle.fillPolygon?.let { fill ->
                val alpha = (if (handle.id.isSolidHandle()) 0.72f else 0.22f) * emphasis
                fillPolygon(scope, fill, base.withAlpha(alpha))
            }
            for (stroke in handle.renderLines) {
                val strokeEmphasis = if (highlighted) 1f else emphasis * stroke.emphasis
                strokeLine(
                    scope,
                    stroke.points,
                    base.withAlpha(base.alpha * strokeEmphasis),
                    handle.width * (0.55f + 0.45f * strokeEmphasis),
                    stroke.closed,
                )
            }
        }
    }

    private fun UiColor.withAlpha(alpha: Float) = UiColor(red, green, blue, alpha)

    private fun GizmoHandleId.isRotation(): Boolean =
        this == GizmoHandleId.ROTATE_X || this == GizmoHandleId.ROTATE_Y || this == GizmoHandleId.ROTATE_Z

    private fun GizmoHandleId.isSolidHandle(): Boolean = when (this) {
        GizmoHandleId.AXIS_X, GizmoHandleId.AXIS_Y, GizmoHandleId.AXIS_Z,
        GizmoHandleId.SCALE_X, GizmoHandleId.SCALE_Y, GizmoHandleId.SCALE_Z, GizmoHandleId.SCALE_UNIFORM -> true
        else -> false
    }

    /** Thinner bounding-box lines when the box is small/distant on screen. */
    private fun boundsLineWidth(bounds: AABB): Float {
        val span = projectedBoundsSpan(bounds) ?: return 1.3f
        return (span / 220f).coerceIn(0.7f, 1.6f)
    }

    private fun projectedBoundsSpan(bounds: AABB): Float? {
        val projector = WorldToScreenProjector
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        val corners = arrayOf(
            Vec3(bounds.minX, bounds.minY, bounds.minZ), Vec3(bounds.maxX, bounds.maxY, bounds.maxZ),
            Vec3(bounds.maxX, bounds.minY, bounds.minZ), Vec3(bounds.minX, bounds.maxY, bounds.maxZ),
        )
        for (corner in corners) {
            val p = projector.project(corner) ?: return null
            if (!p.onScreen) return null
            minX = minOf(minX, p.x); minY = minOf(minY, p.y)
            maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
        }
        return hypot(maxX - minX, maxY - minY)
    }


    /** Screen span beyond which a polyline is treated as degenerate and skipped (guards the tiler). */
    private const val MAX_DRAW_SPAN = 8000f

    private fun withinDrawBounds(points: List<Pt>): Boolean {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (point in points) {
            if (!point.x.isFinite() || !point.y.isFinite()) return false
            minX = minOf(minX, point.x); minY = minOf(minY, point.y)
            maxX = maxOf(maxX, point.x); maxY = maxOf(maxY, point.y)
        }
        return (maxX - minX) <= MAX_DRAW_SPAN && (maxY - minY) <= MAX_DRAW_SPAN
    }

    private fun strokeLine(
        scope: UiCanvasDrawScope,
        points: List<Pt>,
        color: UiColor,
        width: Float,
        closed: Boolean = false,
    ) {
        if (points.size < 2 || !withinDrawBounds(points)) return
        val shape = GenericShape {
            points.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            if (closed) close()
        }
        scope.drawShape(shape, UiPaint.Color(color), UiDrawStyle.Stroke(width))
    }

    private fun fillPolygon(scope: UiCanvasDrawScope, points: List<Pt>, color: UiColor) {
        if (points.size < 3 || !withinDrawBounds(points)) return
        val shape = GenericShape {
            points.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
            close()
        }
        scope.drawShape(shape, UiPaint.Color(color), UiDrawStyle.Fill)
    }

    private fun updateLabel(entry: GizmoEntry, drag: GizmoDrag) {
        val origin = entry.working?.translation ?: return
        val screen = WorldToScreenProjector.project(
            origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble(),
        ) ?: return
        if (!screen.onScreen) return
        labelState = OverlayLabelState(screen.x, screen.y, drag.labelValue)
    }

    private fun crosshairMode(): Boolean = Minecraft.getInstance().screen == null

    private fun pointerOverIde(physX: Float, physY: Float): Boolean =
        HollowIdeOverlay.isVisible() && HollowIdeOverlay.isMouseOver(physX, physY)

    private fun pointerLogical(physX: Float, physY: Float): Pair<Float, Float> =
        if (crosshairMode()) {
            WorldToScreenProjector.screenCenter()
        } else {
            val point = hollowIdeOverlayPoint(physX, physY)
            point.x to point.y
        }

    private fun isEditorAvailable(): Boolean {
        val minecraft = Minecraft.getInstance()
        return isEnabled &&
                minecraft.level != null &&
                minecraft.player?.hasPermissions(PlayerPermissions.GAMEMASTER) == true &&
                (minecraft.screen == null || minecraft.screen is ChatScreen)
    }

    internal fun resolveTarget(model: Model?): TransformGizmoTarget =
        if (model != null) TransformGizmoTarget(TransformGizmoTargetType.MODEL, "Model", MODEL_ICON)
        else TransformGizmoTarget(TransformGizmoTargetType.TRANSFORM, "Transform", TRANSFORM_ICON)

    private fun computeTargetBounds(
        target: TransformGizmoTarget,
        resolved: ResolvedNodeTransform,
        localBounds: Pair<Vec3f, Vec3f>?,
    ): AABB =
        when (target.type) {
            TransformGizmoTargetType.MODEL -> buildNodeRenderBounds(localBounds, resolved.transform)
            TransformGizmoTargetType.TRANSFORM -> buildGenericBounds(resolved.transform)
        }

    /** The local box of what is drawn for a node, or null while nothing has drawn it yet. */
    private fun localBoundsOf(entity: Entity?, nodeId: UUID, model: Model?): Pair<Vec3f, Vec3f>? {
        val host = entity ?: return null
        val name = model?.model ?: return null
        return host.modelInstanceOrNull(nodeId, name)?.attachment?.calculateBounds()
    }

    /**
     * While dragging, the bounding box and light preview follow the live working transform instead of
     * the snapshot (which lags a frame behind the packet round-trip), so the box never flicks away.
     */
    private fun displayResolved(
        entry: GizmoEntry,
        resolved: ResolvedNodeTransform,
        dragging: Boolean,
    ): ResolvedNodeTransform {
        val working = entry.working
        if (!dragging || working == null) return resolved
        val trs = TrsTransformF().setCompositionOf(working.translation, working.rotation, working.scale)
        return ResolvedNodeTransform(trs, resolved.light)
    }

    private fun syncVisibleEntries() {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: run { entries.clear(); return }
        val seen = linkedSetOf<GizmoEntryId>()
        val service = NodeRuntimeState.service(level)
        val partialTick = TickHandler.partialTick

        service.records.forEach { record ->
            val snapshot = service.snapshot(record.snapshotId) ?: return@forEach
            val hostEntityUuid = snapshot.hostEntityUuidOrNull() ?: record.hostEntityUuid
            val claimedNodes = hashSetOf<UUID>()

            snapshot.modelNodes().forEach modelNode@{ modelNode ->
                val resolved =
                    resolveNodeTransform(level, hostEntityUuid, modelNode.transform, partialTick) ?: return@modelNode
                val entryId = GizmoEntryId(record.snapshotId, modelNode.nodeId)
                val entry = entries.getOrPut(entryId) { GizmoEntry(entryId) }
                val target = resolveTarget(modelNode.model)
                entry.hostEntityUuid = hostEntityUuid
                entry.entityId = record.hostEntity?.id
                entry.snapshot = snapshot
                entry.target = target
                entry.visible = true
                entry.modelComponent = modelNode.model
                val dragging = draggingKey == entryId
                val display = displayResolved(entry, resolved, dragging)
                val bounds = localBoundsOf(record.hostEntity, modelNode.nodeId, modelNode.model)
                entry.updateFromResolved(display, computeTargetBounds(target, display, bounds), dragging)
                seen += entryId
                claimedNodes += modelNode.nodeId
            }

        }

        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (entryId, _) = iterator.next()
            if (entryId in seen) continue
            if (hoveredKey == entryId) hoveredKey = null
            if (draggingKey == entryId) {
                draggingKey = null; currentDrag = null
            }
            if (activeKey == entryId) activeKey = null
            if (contextMenuState?.entryId == entryId) contextMenuState = null
            iterator.remove()
        }
    }

    private fun refreshFromRuntime(entry: GizmoEntry) {
        val level = Minecraft.getInstance().level ?: return
        val snapshot = NodeRuntimeState.service(level).snapshot(entry.snapshotId) ?: return
        val nodeSnapshot = snapshot.nodeByIdOrNull(entry.nodeId) ?: return
        val transform =
            nodeSnapshot.components.filterIsInstance<TransformComponent>().firstOrNull() ?: TransformComponent()
        val model = nodeSnapshot.components.filterIsInstance<Model>().firstOrNull()
        val target = resolveTarget(model)
        val hostEntityUuid = snapshot.hostEntityUuidOrNull() ?: entry.hostEntityUuid
        val resolved = resolveNodeTransform(level, hostEntityUuid, transform, TickHandler.partialTick) ?: return
        entry.hostEntityUuid = hostEntityUuid
        entry.snapshot = snapshot
        entry.target = target
        entry.modelComponent = model
        entry.lastAppliedTransform = transform
        val host = entry.entityId?.let(level::getEntity)
        val bounds = localBoundsOf(host, entry.nodeId, model)
        entry.updateFromResolved(resolved, computeTargetBounds(target, resolved, bounds), dragging = false)
    }

    private fun pickBounds(x: Float, y: Float): GizmoEntryId? {
        var best: GizmoEntryId? = null
        var bestDepth = Float.POSITIVE_INFINITY
        for ((entryId, entry) in entries) {
            if (!entry.visible) continue
            val bounds = entry.lastBounds ?: continue
            val depth = boundsScreenDepthAt(bounds, x, y) ?: continue
            if (depth < bestDepth) {
                bestDepth = depth
                best = entryId
            }
        }
        return best
    }

    /** Screen depth of [bounds] under (x,y) via its projected screen rectangle, or null if outside. */
    private fun boundsScreenDepthAt(bounds: AABB, x: Float, y: Float): Float? {
        val projector = WorldToScreenProjector
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var depthSum = 0f
        var count = 0
        val corners = arrayOf(
            Vec3(bounds.minX, bounds.minY, bounds.minZ), Vec3(bounds.maxX, bounds.minY, bounds.minZ),
            Vec3(bounds.maxX, bounds.minY, bounds.maxZ), Vec3(bounds.minX, bounds.minY, bounds.maxZ),
            Vec3(bounds.minX, bounds.maxY, bounds.minZ), Vec3(bounds.maxX, bounds.maxY, bounds.minZ),
            Vec3(bounds.maxX, bounds.maxY, bounds.maxZ), Vec3(bounds.minX, bounds.maxY, bounds.maxZ),
        )
        for (corner in corners) {
            val p = projector.project(corner) ?: return null
            if (!p.onScreen) return null
            minX = minOf(minX, p.x); minY = minOf(minY, p.y)
            maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
            depthSum += p.depth; count++
        }
        val padding = 2f
        if (x < minX - padding || x > maxX + padding || y < minY - padding || y > maxY + padding) return null
        return depthSum / count
    }

    private fun applyFromGizmo(entry: GizmoEntry, values: GizmoTransformValues) {
        val level = Minecraft.getInstance().level ?: return
        val worldPosition =
            Vec3(values.translation.x.toDouble(), values.translation.y.toDouble(), values.translation.z.toDouble())
        val updatedTransform = worldTransformToComponent(
            level = level,
            hostEntityUuid = entry.hostEntityUuid,
            worldPosition = worldPosition,
            worldRotation = values.rotation,
            worldScale = values.scale,
            partialTick = TickHandler.partialTick,
        ) ?: return
        if (updatedTransform == entry.lastAppliedTransform) return
        commitLocalTransform(entry, updatedTransform)
    }

    /** Writes [transform] as the node's local transform: updates the snapshot, materializes and syncs. */
    private fun commitLocalTransform(entry: GizmoEntry, transform: TransformComponent) {
        val level = Minecraft.getInstance().level ?: return
        entry.lastAppliedTransform = transform
        val service = NodeRuntimeState.service(level)
        val snapshot = service.snapshot(entry.snapshotId) ?: return
        val updatedSnapshot = snapshot.withOrReplace(transform, entry.nodeId)
        entry.snapshot = updatedSnapshot
        service.materialize(updatedSnapshot)
        NodeTransformUpdatePacket(entry.snapshotId, entry.nodeId, transform).send()
    }

    private fun resetTransform(entryId: GizmoEntryId) {
        val entry = entries[entryId] ?: return
        val level = Minecraft.getInstance().level ?: return
        val snapshot = NodeRuntimeState.service(level).snapshot(entry.snapshotId) ?: return
        val node = snapshot.nodeByIdOrNull(entry.nodeId) ?: return
        val current = node.components.filterIsInstance<TransformComponent>().firstOrNull() ?: TransformComponent()
        val reset = if (entry.hostEntityUuid != null) {
            TransformComponent()
        } else {
            TransformComponent(translation = current.translation, rotation = QuatF.IDENTITY, scale = Vec3f(1f, 1f, 1f))
        }
        commitLocalTransform(entry, reset)
        refreshFromRuntime(entry)
        contextMenuState = null
    }

    private class GizmoEntry(val entryId: GizmoEntryId) {
        val snapshotId: UUID get() = entryId.snapshotId
        val nodeId: UUID get() = entryId.nodeId

        var hostEntityUuid: UUID? = null
        var entityId: Int? = null
        var snapshot: Snapshot? = null
        var target: TransformGizmoTarget? = null
        var modelComponent: Model? = null
        var visible: Boolean = false
        var lastResolved: ResolvedNodeTransform? = null
        var lastBounds: AABB? = null
        var lastAppliedTransform: TransformComponent? = null
        var working: GizmoTransformValues? = null

        fun updateFromResolved(resolved: ResolvedNodeTransform, bounds: AABB, dragging: Boolean) {
            lastResolved = resolved
            lastBounds = bounds
            if (!dragging || working == null) {
                working = GizmoTransformValues(
                    Vec3f(resolved.transform.translation),
                    QuatF(resolved.transform.rotation),
                    Vec3f(resolved.transform.scale),
                )
            }
        }
    }

    private data class GizmoEntryId(val snapshotId: UUID, val nodeId: UUID)
    private data class ContextMenuState(val entryId: GizmoEntryId, val anchor: UiRect)
    private data class OverlayLabelState(val x: Float, val y: Float, val value: Double)
}

enum class GizmoEditMode {
    TRANSLATE,
    ROTATE,
    SCALE,
}

private fun formatLabelValue(value: Double): String {
    val absValue = abs(value)
    val precision = when {
        absValue >= 1000.0 -> 0
        absValue >= 100.0 -> 1
        absValue >= 1.0 -> 2
        absValue >= 0.01 -> 3
        else -> 4
    }
    return "%.${precision}f".format(Locale.ROOT, value)
}

private fun buildGenericBounds(transform: TrsTransformF): AABB {
    val position = transform.translation
    val scale = transform.scale
    val radius = max(max(abs(scale.x), abs(scale.y)), abs(scale.z)).coerceAtLeast(0.5f).toDouble() * 0.125
    return AABB(
        position.x - radius, position.y - radius, position.z - radius,
        position.x + radius, position.y + radius, position.z + radius,
    )
}
