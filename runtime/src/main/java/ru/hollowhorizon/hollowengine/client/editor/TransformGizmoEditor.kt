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
import ru.hollowhorizon.hollowengine.client.ui.ide.hollowIdeWorldPoint
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.UiShadow
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.common.config.HollowEngineConfig
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderLevelStageEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderStage
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderTickEvent
import ru.hollowhorizon.hollowengine.common.attachments.binding.*
import ru.hollowhorizon.hollowengine.common.attachments.components.*
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.Snapshot
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectEntity
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjects
import ru.hollowhorizon.hollowengine.common.utils.isProduction
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

    /** A transform from the keyboard under way, and where its hint is shown. */
    private var keyboard: GizmoKeyboardTransform? = null
    private var keyboardHint by mutableStateOf<KeyboardHintState?>(null)
    private var contextMenuState by mutableStateOf<ContextMenuState?>(null)

    private var modesValue by mutableStateOf(setOf(GizmoEditMode.TRANSLATE))
    private var enabledValue by mutableStateOf(false)

    val isEnabled: Boolean
        get() {
            ensureInitialized()
            return enabledValue && !isProduction
        }

    fun setEnabled(enabled: Boolean) {
        ensureInitialized()
        if (enabledValue == enabled) return
        enabledValue = enabled
        HollowEngineConfig.gizmoEnabled = enabled
        if (!enabled) cancelInteraction()
    }

    /** The manipulators drawn together; never empty, the last one stays remembered while the gizmo is off. */
    val modes: Set<GizmoEditMode> get() = modesValue

    /**
     * Entities the gizmo already boxes, because a node of theirs is on screen.
     */
    internal fun boxedEntityIds(): Set<Int> {
        if (!isEditorAvailable()) return emptySet()
        return entries.values.filter { it.visible }.mapNotNullTo(HashSet()) { it.entityId }
    }

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

    fun isModeShown(mode: GizmoEditMode): Boolean = isEnabled && mode in modesValue

    /**
     * Shows or hides gizmo manipulator and it's mode.
     */
    fun setModeShown(mode: GizmoEditMode, shown: Boolean) {
        ensureInitialized()
        when {
            shown && !isEnabled -> {
                setModes(setOf(mode))
                setEnabled(true)
            }

            shown -> setModes(modesValue + mode)
            !isEnabled || mode !in modesValue -> Unit
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
                GizmoManipulator.World.update(drag, cx, cy, hollowIdeModifierMask())?.let { values ->
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
        if (!isInitialized || !isEditorAvailable() || crosshairMode() || pointerOverIde()) {
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
        if (!crosshairMode() && pointerOverIde() && action == GLFW.GLFW_PRESS) return false
        if (!crosshairMode() && overlay.handleMouseButton(physX, physY, button, action)) return true
        val (x, y) = pointerLogical(physX, physY)
        keyboard?.let { transform ->
            return action != GLFW.GLFW_PRESS || finishKeyboardTransform(transform.click(button))
        }
        return when (action) {
            GLFW.GLFW_PRESS -> onPress(x, y, button)
            GLFW.GLFW_RELEASE -> onRelease(button)
            else -> false
        }
    }

    /**
     * The editor took the pointer away, so nothing in the world is under it any more. A drag keeps
     * its handle: it is being held, not hovered.
     */
    fun releasePointer() {
        if (currentDrag != null) return
        hoveredHandleId = null
        hoveredKey = null
    }

    fun handleMouseMove(physX: Float, physY: Float): Boolean {
        ensureInitialized()
        lastPhysX = physX
        lastPhysY = physY
        if (!isEditorAvailable() || crosshairMode()) return false
        overlay.handleMouseMove(physX, physY)
        val (x, y) = pointerLogical(physX, physY)
        keyboard?.let { transform ->
            moveKeyboardTransform(transform, hollowIdeModifierMask())
            return true
        }
        val drag = currentDrag
        if (drag == null && pointerOverIde()) {
            hoveredHandleId = null
            hoveredKey = null
            return false
        }
        if (drag != null) {
            val entry = draggingKey?.let(entries::get)
            val values = GizmoManipulator.World.update(drag, x, y, hollowIdeModifierMask())
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
        if (action == GLFW.GLFW_PRESS && handleTransformKey(key, modifiers)) return true
        return overlay.handleKey(key, scanCode, action, modifiers)
    }

    /** T, R and S on the selected node, and the keys of a transform under way. */
    private fun handleTransformKey(key: Int, modifiers: Int): Boolean {
        val transform = keyboard
        if (transform != null) {
            val result = transform.key(key)
            if (result == GizmoKeyResult.CHANGED) {
                draggingKey?.let(entries::get)?.let { applyFromGizmo(it, transform.start) }
                keyboardHint = keyboardHint?.copy(text = transform.hint)
                moveKeyboardTransform(transform, modifiers)
            }
            return finishKeyboardTransform(result) || result == GizmoKeyResult.CHANGED
        }
        if (modifiers != 0 || pointerOverIde()) return false

        val mode = GizmoKeyboardTransform.modeFor(key) ?: return false
        val entry = activeKey?.let(entries::get)?.takeIf { it.visible } ?: return false
        val working = entry.working ?: return false
        val (x, y) = pointerLogical(lastPhysX, lastPhysY)
        keyboard = GizmoKeyboardTransform(mode, working, x, y, WorldToScreenProjector, GizmoGeometry.World, GizmoManipulator.World)
        keyboardHint = KeyboardHintState(x, y, keyboard?.hint.orEmpty())
        draggingKey = entry.entryId
        contextMenuState = null
        return true
    }

    private fun moveKeyboardTransform(transform: GizmoKeyboardTransform, modifiers: Int) {
        val entry = draggingKey?.let(entries::get) ?: return
        val (x, y) = pointerLogical(lastPhysX, lastPhysY)
        val values = transform.update(x, y, modifiers) ?: return
        entry.working = values
        applyFromGizmo(entry, values)
    }

    /** Ends the transform from the keyboard when [result] says so; true when it did. */
    private fun finishKeyboardTransform(result: GizmoKeyResult): Boolean {
        val transform = keyboard ?: return false
        val entry = draggingKey?.let(entries::get)
        when (result) {
            GizmoKeyResult.CONFIRMED -> Unit
            GizmoKeyResult.CANCELLED -> entry?.let {
                it.working = transform.start
                applyFromGizmo(it, transform.start)
            }

            else -> return false
        }
        keyboard = null
        keyboardHint = null
        draggingKey = null
        entry?.let(::finishEdit)
        return true
    }

    fun handleChar(codePoint: Int, modifiers: Int): Boolean {
        if (!isEditorAvailable() || crosshairMode()) return false
        return overlay.handleChar(codePoint, modifiers)
    }

    fun shouldBlockScreenInput(physX: Float, physY: Float): Boolean {
        if (!isInitialized || !isEditorAvailable()) return false
        if (draggingKey != null) return true
        if (!crosshairMode()) {
            if (pointerOverIde()) return false
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
                    currentDrag = GizmoManipulator.World.begin(handle, entry.working ?: return false, x, y)
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
                activeKey = hit
                contextMenuState = null
                entries[hit]?.entityId?.let(WorldInspector::select)
                true
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
        entry?.let(::finishEdit)
        return true
    }

    /** A drag or a keyboard transform of [entry] is over: what shows it reads it again. */
    private fun finishEdit(entry: GizmoEntry) {
        refreshFromRuntime(entry)
        entry.worldObject?.let(WorldObjectEditing::finishGizmo)
    }

    /** Puts the gizmo on the entity with [entityId], when it is boxed; the scene window selects through this. */
    fun select(entityId: Int) {
        val entryId = entries.entries.firstOrNull { it.value.entityId == entityId }?.key ?: return
        activeKey = entryId
        contextMenuState = null
    }

    private fun updateHover(x: Float, y: Float) {
        hoveredHandleId = activeEntryHandleAt(x, y)?.id
        hoveredKey = if (hoveredHandleId != null) activeKey else pickBounds(x, y)
    }

    private fun activeEntryHandleAt(x: Float, y: Float): GizmoHandle? {
        val entry = activeKey?.let(entries::get)?.takeIf { it.visible } ?: return null
        val working = entry.working ?: return null
        val handles = GizmoGeometry.World.buildHandles(working.translation, working.rotation, modes)
        return GizmoPicker.pick(handles, x, y)
    }

    /** A world object gets the menu it has everywhere; any other node, the gizmo's own. */
    private fun openContextMenu(entryId: GizmoEntryId, x: Float, y: Float) {
        val target = entries[entryId]?.worldObject
        if (target != null) {
            contextMenuState = null
            WorldInspector.select(target.id)
            WorldObjectContextMenu.open(target, x, y)
            return
        }
        contextMenuState = ContextMenuState(entryId, UiRect(x, y, 0f, 0f))
    }

    private fun clearSelection() {
        activeKey = null
        hoveredKey = null
        hoveredHandleId = null
        contextMenuState = null
    }

    private fun cancelInteraction() {
        keyboard = null
        keyboardHint = null
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

            keyboardHint?.let { hint ->
                Text(
                    hint.text,
                    modifier = Modifier.position((hint.x + 16f).px, (hint.y + 16f).px)
                        .padding(6.px, 3.px)
                        .background(UiColor(0.08f, 0.10f, 0.14f, 0.9f))
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
                for (edge in GizmoGeometry.World.buildBoundsEdges(bounds)) GizmoRenderer.strokeLine(scope, edge, boundsColor, width)
            }
        }

        val active = activeKey?.let(entries::get)?.takeIf { it.visible } ?: return
        val working = active.working ?: return

        keyboard?.let { transform ->
            transform.draw(scope)
            return
        }

        val drag = currentDrag
        if (drag != null && draggingKey == active.entryId) {
            GizmoRenderer.drawRotationSector(scope, GizmoGeometry.World, WorldToScreenProjector, drag)
        }

        val handles = GizmoGeometry.World.buildHandles(working.translation, working.rotation, modes)
        GizmoRenderer.drawHandles(scope, handles, hoveredHandleId, draggingHandleId)
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


    private fun updateLabel(entry: GizmoEntry, drag: GizmoDrag) {
        val origin = entry.working?.translation ?: return
        val screen = WorldToScreenProjector.project(
            origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble(),
        ) ?: return
        if (!screen.onScreen) return
        labelState = OverlayLabelState(screen.x, screen.y, drag.labelValue)
    }

    private fun crosshairMode(): Boolean = Minecraft.getInstance().screen == null

    private fun pointerOverIde(): Boolean = HollowIdeOverlay.holdsPointer()

    private fun pointerLogical(physX: Float, physY: Float): Pair<Float, Float> =
        if (crosshairMode()) {
            WorldToScreenProjector.screenCenter()
        } else {
            val point = hollowIdeWorldPoint(physX, physY)
            point.x to point.y
        }

    private fun isEditorAvailable(): Boolean = isEnabled && EditorMode.isAvailable()

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
            if (record.hostEntity is WorldObjectEntity) return@forEach
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

        val camera = WorldToScreenProjector.cameraPosition
        val reach = WorldObjectEditing.GIZMO_REACH * WorldObjectEditing.GIZMO_REACH
        WorldObjects.all(level).forEach { target ->
            if (target.distanceToSqr(camera) > reach) return@forEach
            val entryId = GizmoEntryId(target.uuid, ROOT_COMPONENT_ID)
            val entry = entries.getOrPut(entryId) { GizmoEntry(entryId) }
            entry.hostEntityUuid = target.uuid
            entry.entityId = target.id
            entry.worldObject = target
            entry.target = TransformGizmoTarget(TransformGizmoTargetType.TRANSFORM, "Object", TRANSFORM_ICON)
            entry.visible = true
            val dragging = draggingKey == entryId
            val resolved = ResolvedNodeTransform(WorldObjectEditing.gizmoTransform(target, partialTick), 0)
            val display = displayResolved(entry, resolved, dragging)
            entry.updateFromResolved(display, WorldObjectEditing.bounds(target, partialTick), dragging)
            seen += entryId
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
        entry.worldObject?.let { target ->
            val resolved = ResolvedNodeTransform(WorldObjectEditing.gizmoTransform(target, TickHandler.partialTick), 0)
            entry.updateFromResolved(resolved, WorldObjectEditing.bounds(target, TickHandler.partialTick), dragging = false)
            return
        }
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
        entry.worldObject?.let { target ->
            if (values == entry.lastAppliedValues) return
            entry.lastAppliedValues = values
            WorldObjectEditing.applyGizmo(target, values)
            return
        }
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
        entry.worldObject?.let { target ->
            WorldObjectEditing.resetRotationAndScale(target)
            refreshFromRuntime(entry)
            contextMenuState = null
            return
        }
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

        /** Set when the entry is a world object itself, which the gizmo moves whole. */
        var worldObject: WorldObjectEntity? = null
        var lastAppliedValues: GizmoTransformValues? = null
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
    private data class KeyboardHintState(val x: Float, val y: Float, val text: String)
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
