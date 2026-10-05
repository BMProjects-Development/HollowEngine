package ru.hollowhorizon.hollowengine.client.editor

import net.minecraft.client.Minecraft
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.common.entities.objects.ObjectPose
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectEntity
import ru.hollowhorizon.hollowengine.common.events.ClientOnly

/** The steps world objects themselves leave in [WorldHistory]: where one was put, and what it was put under. */
@ClientOnly
internal object WorldObjectHistory {
    /** The object the gizmo holds and where it was when the drag began, so letting go makes one step of it. */
    private var gizmoStart: Pair<Int, ObjectPose>? = null

    fun beginGizmo(target: WorldObjectEntity) {
        gizmoStart = target.id to capture(target)
    }

    fun finishGizmo(target: WorldObjectEntity) {
        val (id, before) = gizmoStart ?: return
        gizmoStart = null
        if (id == target.id) recordPose(target, before)
    }

    /** Records that [target] was moved from [before] to where it is now. */
    fun recordPose(target: WorldObjectEntity, before: ObjectPose) {
        val after = capture(target)
        if (same(before, after)) return
        WorldHistory.record(PoseStep(target.id, before, after))
    }

    /** Puts [target] under [parent], as a step that can be taken back. */
    fun reparent(target: WorldObjectEntity, parent: WorldObjectEntity?) {
        val before = target.parent?.id
        if (before == parent?.id || !WorldObjectEditing.sendParent(target.id, parent?.id)) return
        WorldHistory.record(ParentStep(target.id, before, parent?.id))
    }

    private class PoseStep(override val entityId: Int, val before: ObjectPose, val after: ObjectPose) : WorldHistory.Step {
        override fun undo(): Boolean = place(before)

        override fun redo(): Boolean = place(after)

        private fun place(pose: ObjectPose): Boolean {
            val target = objectOf(entityId) ?: return false
            WorldObjectEditing.place(target, pose)
            WorldObjectEditing.refreshInspector(target)
            return true
        }
    }

    private class ParentStep(override val entityId: Int, val before: Int?, val after: Int?) : WorldHistory.Step {
        override fun undo(): Boolean = WorldObjectEditing.sendParent(entityId, before)

        override fun redo(): Boolean = WorldObjectEditing.sendParent(entityId, after)
    }

    private fun objectOf(entityId: Int): WorldObjectEntity? = Minecraft.getInstance().level?.getEntity(entityId) as? WorldObjectEntity

    /** The pose as it is now; [WorldObjectEntity.pose] may hand out the vectors it keeps. */
    fun capture(target: WorldObjectEntity): ObjectPose {
        val pose = target.pose(1f)
        return ObjectPose(Vector3d(pose.position), Quaternionf(pose.rotation), Vector3f(pose.scale))
    }

    private fun same(a: ObjectPose, b: ObjectPose) = a.position == b.position && a.rotation == b.rotation && a.scale == b.scale
}
