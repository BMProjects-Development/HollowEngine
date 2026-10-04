package ru.hollowhorizon.hollowengine.client.editor

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.entity.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOverlay
import ru.hollowhorizon.hollowengine.client.ui.ide.hollowIdeWorldPoint
import ru.hollowhorizon.hollowengine.client.ui.inspector.AssetPickerDialog
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorSelection
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorTarget
import ru.hollowhorizon.hollowengine.client.ui.inspector.LocalInspectorHost
import ru.hollowhorizon.hollowengine.common.attachments.editor.EntityEditorSnapshot
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectEntity
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjects
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderLevelStageEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderStage

/** How far the editor reaches: what is boxed is what a click can pick. */
private const val EditorReach = 24.0

/** How far beside an entity still counts as pointing at it. */
private const val PickSlack = 0.25

/** How editor names itself to the shared inspector. */
private const val Source = "world"


/**
 * Editor mode in the world itself.
 */
@ClientOnly
object WorldInspector {
    private var session by mutableStateOf<EntityEditorSession?>(null)
    private var hovered by mutableStateOf<Entity?>(null)
    private var boxed by mutableStateOf<List<Entity>>(emptyList())
    private var gizmoOwned by mutableStateOf<Set<Int>>(emptySet())
    private var listening = false
    private var lastPointerX = 0f
    private var lastPointerY = 0f

    private val overlay: HollowUiWorldOverlay by lazy {
        HollowUiWorldOverlay(manageCursor = false).apply { setContent { Outlines() } }
    }

    /** Entity, that inspector is currently editing. */
    val selected: Entity? get() = session?.let { Minecraft.getInstance().level?.getEntity(it.entityId) }

    @SubscribeEvent
    fun onRenderLevel(event: RenderLevelStageEvent) {
        if (event.stage != RenderStage.AFTER_LEVEL) return
        if (!EditorMode.isActive()) {
            hovered = null
            boxed = emptyList()
            gizmoOwned = emptySet()
            return
        }
        hovered = if (pointerHeldByEditor()) null else entityAt(lastPointerX, lastPointerY)
        gizmoOwned = TransformGizmoEditor.boxedEntityIds()
        boxed = boxableEntities()
        overlay.render()
    }

    private fun boxableEntities(): List<Entity> {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return emptyList()
        val level = minecraft.level ?: return emptyList()
        return level.getEntities(player, player.boundingBox.inflate(EditorReach)) {
            (it.isPickable || it is WorldObjectEntity) && it != player && it.id !in gizmoOwned
        }
    }

    fun handleMouseMove(physX: Float, physY: Float) {
        lastPointerX = physX
        lastPointerY = physY
    }

    private fun pointerHeldByEditor(): Boolean =
        Minecraft.getInstance().screen != null && HollowIdeOverlay.holdsPointer()

    fun pickAt(physX: Float, physY: Float, button: Int, action: Int): Boolean {
        if (action != GLFW.GLFW_PRESS) return false
        if (!EditorMode.isActive()) return false
        if (HollowIdeOverlay.holdsPointer()) return false
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) return openObjectMenu(physX, physY)
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false

        val entity = hovered ?: run {
            close()
            return false
        }
        EntityEditorClient.request(entity, EntityEditorClient.Destination.INSPECTOR)
        return true
    }

    /**
     * A right click on a world object, with the pointer free, selects it and opens its menu. With the
     * crosshair the right click stays the game's.
     */
    private fun openObjectMenu(physX: Float, physY: Float): Boolean {
        if (Minecraft.getInstance().screen == null) return false
        val target = hovered as? WorldObjectEntity ?: return false
        select(target.id)
        val point = hollowIdeWorldPoint(physX, physY)
        WorldObjectContextMenu.open(target, point.x, point.y)
        return true
    }

    internal fun accept(state: EntityEditorSnapshot) {
        listen()
        val current = session
        val shown = if (current != null && current.entityId == state.entityId) {
            current.accept(state)
            current
        } else {
            EntityEditorSession(state).also { session = it }
        }
        InspectorSelection.publish(Source, entityInspectorTarget(shown))
    }

    fun select(entityId: Int) {
        val entity = Minecraft.getInstance().level?.getEntity(entityId) ?: return
        if (holds(entityId)) return
        EntityEditorClient.request(entity, EntityEditorClient.Destination.INSPECTOR)
    }

    fun holds(entityId: Int): Boolean = session?.entityId == entityId

    /** The entity in the inspector; a snapshot read, so composition that shows it follows the selection. */
    val selectedEntityId: Int? get() = session?.entityId

    internal val inspectorSession: EntityEditorSession? get() = session

    fun close() {
        val previous = session
        previous?.closeSlots()
        previous?.let { EntityEditorClient.forget(it.entityId) }
        session = null
        InspectorSelection.release(Source)
    }

    private fun listen() {
        if (listening) return
        listening = true
        EditorMode.onChanged { enabled -> if (!enabled) close() }
    }

    /** The entity's components and scripts, ready for whichever panel ends up drawing them. */
    private fun entityInspectorTarget(session: EntityEditorSession) = InspectorTarget(
        id = "entity-${session.entityId}",
        title = session.snapshot.title,
        subtitle = session.snapshot.typeId,
        styles = listOf(WidgetStylesheet, EntityEditorStylesheet),
    ) {
        CompositionLocalProvider(
            LocalEntityEditorSession provides session,
            LocalInspectorHost provides session,
        ) {
            EntitySidebar(session, width = null)
            session.pendingPicker?.let { picker -> AssetPickerDialog(picker) { session.pendingPicker = null } }
            if (session.slotSessionId != null) InventoryDialog(session)
        }
    }

    @Composable
    private fun Outlines() {
        val owned = gizmoOwned
        val current = selected?.takeIf { it.id !in owned }
        val over = hovered?.takeIf { it.id !in owned }
        val all = boxed

        Box(
            id = "world-inspector-outlines",
            modifier = Modifier.size(100.percent, 100.percent).inputTransparent()
                .drawBehind(key = "outline-${current?.id}-${over?.id}-${all.size}-${all.firstOrNull()?.id}") {
                    all.forEach { entity ->
                        if (entity != current && entity != over) outline(this, entity, GizmoColors.BOUNDS)
                    }
                    if (over != null && over != current) outline(this, over, GizmoColors.BOUNDS_HOVER)
                    current?.let { outline(this, it, GizmoColors.BOUNDS_ACTIVE) }
                },
        )
    }

    private fun outline(scope: UiCanvasDrawScope, entity: Entity, color: UiColor) {
        val bounds = outlineBounds(entity)
        for (edge in GizmoGeometry.World.buildBoundsEdges(bounds)) GizmoRenderer.strokeLine(scope, edge, color, 1.2f)
    }

    /** A world object is outlined by the same box the gizmo draws; any other entity, by its own box with some slack. */
    private fun outlineBounds(entity: Entity): AABB =
        (entity as? WorldObjectEntity)?.let { WorldObjectEditing.bounds(it, TickHandler.partialTick) }
            ?: entity.boundingBox.inflate(PickSlack)

    private fun pickBounds(entity: Entity): AABB =
        if (entity is WorldObjectEntity) outlineBounds(entity).inflate(PickSlack) else outlineBounds(entity)

    private fun entityAt(physX: Float, physY: Float): Entity? {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return null
        val player = minecraft.player ?: return null
        val (origin, end) = pickRay(player, physX, physY) ?: return null
        val searched = AABB(origin, end).inflate(1.0)

        val objects = WorldObjects.all(level).filter { it.distanceToSqr(player) <= EditorReach * EditorReach }
        val candidates = LinkedHashSet<Entity>(level.getEntities(player, searched) { it != player }).apply { addAll(objects) }

        var best: Entity? = null
        var bestDistance = Double.MAX_VALUE
        candidates.forEach { candidate ->
            val hit = pickBounds(candidate).clip(origin, end).orElse(null) ?: return@forEach
            val distance = origin.distanceToSqr(hit)
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return best
    }

    private fun pickRay(player: Entity, physX: Float, physY: Float): Pair<Vec3, Vec3>? {
        if (Minecraft.getInstance().screen == null) {
            val origin = player.eyePosition
            return origin to origin.add(player.lookAngle.scale(EditorReach))
        }
        val point = hollowIdeWorldPoint(physX, physY)
        val ray = WorldToScreenProjector.screenRay(point.x, point.y) ?: return null
        return ray.origin to ray.origin.add(ray.direction.scale(EditorReach))
    }
}
