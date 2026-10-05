package ru.hollowhorizon.hollowengine.client.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.client.Minecraft
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.models.internal.v2.modelInstanceOrNull
import ru.hollowhorizon.hollowengine.client.render.buildNodeRenderBounds
import ru.hollowhorizon.hollowengine.client.render.resolveNodeWorldTransform
import ru.hollowhorizon.hollowengine.client.ui.entity.EntityEditorClient
import ru.hollowhorizon.hollowengine.client.ui.notification.HollowNotifications
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.binding.ROOT_COMPONENT_ID
import ru.hollowhorizon.hollowengine.common.attachments.binding.modelOrNull
import ru.hollowhorizon.hollowengine.common.attachments.binding.transformOrNull
import ru.hollowhorizon.hollowengine.common.attachments.components.TransformComponent
import ru.hollowhorizon.hollowengine.common.entities.objects.DuplicateWorldObjectPacket
import ru.hollowhorizon.hollowengine.common.entities.objects.GoToWorldObjectPacket
import ru.hollowhorizon.hollowengine.common.entities.objects.ObjectPose
import ru.hollowhorizon.hollowengine.common.entities.objects.RemoveWorldObjectsPacket
import ru.hollowhorizon.hollowengine.common.entities.objects.RequestWorldObjectFavoritesPacket
import ru.hollowhorizon.hollowengine.common.entities.objects.SetWorldObjectFavoritePacket
import ru.hollowhorizon.hollowengine.common.entities.objects.SpawnWorldObjectPacket
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectEntity
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectFavorite
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectKind
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectParentPacket
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectPosePacket
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import java.util.UUID

/** What editor does to world objects: placing, selecting, moving, regrouping and removing them. */
@ClientOnly
object WorldObjectEditing {
    /** How far away the gizmo still boxes objects; beyond that they are picked from the scene window. */
    const val GIZMO_REACH = 64.0

    /** How far in front of the player an object is placed when nothing says where. */
    private const val PLACE_DISTANCE = 3.0

    /** The ground probe starts this far above the origin, so an object already standing finds its own floor. */
    private const val GROUND_PROBE_LIFT = 0.05

    /** How far down the ground probe looks, in blocks. */
    private const val GROUND_PROBE_DEPTH = 256.0

    const val LANG = "hollowengine.gui.ide.objects"

    const val WORLD_ICON = "hollowengine:textures/gui/icons/world.svg"
    const val EMPTY_ICON = "hollowengine:textures/gui/icons/box.svg"
    const val FAVORITE_ICON = "hollowengine:textures/gui/icons/star.svg"
    private const val COPY_ICON = "hollowengine:textures/gui/icons/copy.svg"
    private const val RESET_ICON = "hollowengine:textures/gui/icons/reload.svg"
    private const val DROP_ICON = "hollowengine:textures/gui/icons/arrow.svg"
    private const val GO_TO_ICON = "hollowengine:textures/gui/icons/eye.svg"
    private const val LINK_ICON = "hollowengine:textures/gui/icons/link.svg"
    private const val DELETE_ICON = "hollowengine:textures/gui/icons/remove.svg"

    fun spawn(kind: WorldObjectKind, asset: String, x: Double, y: Double, z: Double, parent: WorldObjectEntity? = null) {
        SpawnWorldObjectPacket(kind, asset, x, y, z, parent?.id).send()
    }

    /** An empty object to group others under: at [parent], or in front of the player. */
    fun spawnEmpty(parent: WorldObjectEntity?) {
        val at = parent?.pose(1f)?.position?.let { Vec3(it.x, it.y, it.z) } ?: run {
            val player = Minecraft.getInstance().player ?: return
            player.eyePosition.add(player.lookAngle.scale(PLACE_DISTANCE))
        }
        spawn(WorldObjectKind.EMPTY, "", at.x, at.y, at.z, parent)
    }

    fun delete(objects: Collection<WorldObjectEntity>) {
        if (objects.isEmpty()) return
        if (objects.any { WorldInspector.holds(it.id) }) WorldInspector.close()
        RemoveWorldObjectsPacket(objects.map { it.id }).send()
    }

    fun setParent(target: WorldObjectEntity, parent: WorldObjectEntity?) = WorldObjectHistory.reparent(target, parent)

    /** Asks the server to put the object [entityId] under [parentId]; false when either is gone or it would make a loop. */
    internal fun sendParent(entityId: Int, parentId: Int?): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        val target = level.getEntity(entityId) as? WorldObjectEntity ?: return false
        val parent = parentId?.let { level.getEntity(it) as? WorldObjectEntity ?: return false }
        if (parent != null && (parent === target || parent.isDescendantOf(target))) return false
        WorldObjectParentPacket(target.id, parent?.id).send()
        return true
    }

    /** Puts the object into the inspector and under the gizmo. */
    fun select(entityId: Int) {
        WorldInspector.select(entityId)
        TransformGizmoEditor.select(entityId)
    }

    /** The selected object, when the inspector holds one that is loaded. */
    fun selected(): WorldObjectEntity? {
        val id = WorldInspector.selectedEntityId ?: return null
        return Minecraft.getInstance().level?.getEntity(id) as? WorldObjectEntity
    }

    /** The bookmarked objects of the world, as the server last sent them. */
    var favorites by mutableStateOf<List<WorldObjectFavorite>>(emptyList())
        private set

    fun acceptFavorites(list: List<WorldObjectFavorite>) {
        favorites = list
    }

    fun isFavorite(uuid: UUID): Boolean = favorites.any { it.uuid == uuid }

    fun setFavorite(uuid: UUID, favorite: Boolean) {
        SetWorldObjectFavoritePacket(uuid, favorite).send()
    }

    fun requestFavorites() {
        RequestWorldObjectFavoritesPacket().send()
    }

    fun duplicate(target: WorldObjectEntity) {
        DuplicateWorldObjectPacket(target.id).send()
    }

    /** Takes the player to the object, wherever it is; one that is only bookmarked loads when they get there. */
    fun goTo(uuid: UUID) {
        GoToWorldObjectPacket(uuid).send()
    }

    fun copyId(uuid: UUID) {
        Minecraft.getInstance().keyboardHandler.clipboard = uuid.toString()
        HollowNotifications.info("$LANG.id_copied".lang)
    }

    /** Lowers the object straight down until its origin rests on the first solid thing below it. */
    fun dropToGround(target: WorldObjectEntity) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        val pose = target.pose(1f)
        val from = Vec3(pose.position.x, pose.position.y + GROUND_PROBE_LIFT, pose.position.z)
        val hit = level.clip(ClipContext(from, from.subtract(0.0, GROUND_PROBE_DEPTH, 0.0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
        if (hit.type == HitResult.Type.MISS) return
        place(target, ObjectPose(Vector3d(pose.position.x, hit.location.y, pose.position.z), pose.rotation, pose.scale))
    }

    /**
     * What can be done to [target]: same in scene window and in the world. [inScene] adds what only
     * makes sense from a list, like going to an object that is not in view.
     */
    fun menu(target: WorldObjectEntity, inScene: Boolean): List<UiDropdownItem> = buildList {
        add(UiDropdownItem("$LANG.duplicate".lang, icon = COPY_ICON, shortcut = "Ctrl+D") { duplicate(target) })
        add(UiDropdownItem("$LANG.create_child".lang, icon = EMPTY_ICON) { spawnEmpty(target) })
        if (target.parentId != null) add(UiDropdownItem("$LANG.unparent".lang, icon = WORLD_ICON) { setParent(target, null) })

        add(UiDropdownItem("$LANG.reset_transform".lang, icon = RESET_ICON, separatorBefore = true) { resetRotationAndScale(target) })
        add(UiDropdownItem("$LANG.drop_to_ground".lang, icon = DROP_ICON) { dropToGround(target) })
        if (inScene) add(UiDropdownItem("$LANG.go_to".lang, icon = GO_TO_ICON) { goTo(target.uuid) })

        val favorite = isFavorite(target.uuid)
        add(
            UiDropdownItem(
                if (favorite) "$LANG.favorite_remove".lang else "$LANG.favorite_add".lang,
                icon = FAVORITE_ICON,
                separatorBefore = true,
            ) { setFavorite(target.uuid, !favorite) }
        )
        add(UiDropdownItem("$LANG.copy_id".lang, icon = LINK_ICON) { copyId(target.uuid) })
        add(UiDropdownItem("$LANG.delete".lang, icon = DELETE_ICON, separatorBefore = true, shortcut = "Delete") { delete(listOf(target)) })
    }

    /** Delete and Ctrl+D on the selected object; true when [key] was one of them. */
    fun handleShortcut(key: Int, modifiers: Int): Boolean {
        val target = selected() ?: return false
        val control = modifiers and GLFW.GLFW_MOD_CONTROL != 0
        when {
            key == GLFW.GLFW_KEY_DELETE && modifiers == 0 -> delete(listOf(target))
            key == GLFW.GLFW_KEY_D && control -> duplicate(target)
            else -> return false
        }
        return true
    }

    /** World pose that gizmo handles. */
    internal fun gizmoTransform(target: WorldObjectEntity, partialTick: Float): TrsTransformF {
        val pose = target.pose(partialTick)
        return TrsTransformF().setCompositionOf(
            Vec3f(pose.position.x.toFloat(), pose.position.y.toFloat(), pose.position.z.toFloat()),
            QuatF(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w),
            Vec3f(pose.scale.x, pose.scale.y, pose.scale.z),
        )
    }

    /**
     * Box, that every editor tool outlines and picks the object by.
     */
    fun bounds(target: WorldObjectEntity, partialTick: Float): AABB {
        val snapshot = AttachmentRegistry.entitySnapshot(target.level(), target.uuid)
        val model = snapshot?.modelOrNull()
        val local = model?.let { target.modelInstanceOrNull(ROOT_COMPONENT_ID, it.model)?.attachment?.calculateBounds() }
        if (local == null) {
            val position = target.pose(partialTick).position
            return target.boundingBox.move(position.x - target.x, position.y - target.y, position.z - target.z)
        }
        val transform = snapshot.transformOrNull() ?: TransformComponent()
        return buildNodeRenderBounds(local, resolveNodeWorldTransform(target, transform, partialTick))
    }

    /** Moves the object to where the gizmo left it. */
    internal fun applyGizmo(target: WorldObjectEntity, values: GizmoTransformValues) {
        val pose = ObjectPose(
            position = Vector3d(values.translation.x.toDouble(), values.translation.y.toDouble(), values.translation.z.toDouble()),
            rotation = Quaternionf(values.rotation.x, values.rotation.y, values.rotation.z, values.rotation.w),
            scale = Vector3f(values.scale.x, values.scale.y, values.scale.z),
        )
        place(target, pose)
    }

    /** Clears the object's own rotation and scale, keeping where it stands under its parent. */
    internal fun resetRotationAndScale(target: WorldObjectEntity) {
        val before = WorldObjectHistory.capture(target)
        val local = ObjectPose(target.localPose.position, Quaternionf(), Vector3f(1f))
        place(target, target.parent?.pose(1f)?.compose(local) ?: local)
        WorldObjectHistory.recordPose(target, before)
    }

    /** The gizmo let go of the object: one step back in the history, and the inspector, if it shows it, is out of date now. */
    internal fun finishGizmo(target: WorldObjectEntity) {
        WorldObjectHistory.finishGizmo(target)
        refreshInspector(target)
    }

    internal fun refreshInspector(target: WorldObjectEntity) {
        if (WorldInspector.holds(target.id)) EntityEditorClient.request(target, EntityEditorClient.Destination.INSPECTOR)
    }

    internal fun place(target: WorldObjectEntity, world: ObjectPose) {
        target.setWorldPose(world, snap = true)
        WorldObjectPosePacket(target.id, world).send()
    }
}
