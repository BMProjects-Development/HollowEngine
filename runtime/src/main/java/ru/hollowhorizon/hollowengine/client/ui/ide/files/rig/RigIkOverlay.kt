package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ikTargetMatrix
import ru.hollowhorizon.hollowengine.client.models.internal.v2.links
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.client.render.DebugLines
import ru.hollowhorizon.hollowengine.common.models.IkTargetSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.ikChains
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.hypot

/** One IK target where it stands in the preview: the bone it hangs on, null for the model, and its name. */
internal class PlacedIkTarget(val bone: String?, val id: String, val position: Vec3f)

/** Every IK target of [rig] on the preview's current pose, in model space. */
internal fun previewIkTargets(rig: ModelRig, roots: List<RuntimeNode>): List<PlacedIkTarget> {
    val nodes = roots.nodesByName()
    return rig.allAttachments().mapNotNull { (bone, spec) ->
        if (spec !is IkTargetSpec) return@mapNotNull null
        val place = rig.ikTargetMatrix(spec.id, nodes::get) ?: return@mapNotNull null
        PlacedIkTarget(bone, spec.id, place.getTranslation())
    }
}

/** The target nearest to the pointer within reach of a click, or null. */
internal fun RigPartGizmo.ikTargetAt(targets: List<PlacedIkTarget>, x: Float, y: Float): PlacedIkTarget? =
    targets.map { it to screenOf(it.position) }
        .filter { (_, at) -> hypot(at.first - x, at.second - y) <= PICK_RADIUS }
        .minByOrNull { (_, at) -> hypot(at.first - x, at.second - y) }
        ?.first

/**
 * The IK parts of the rig over the preview: each target as a small cross, each chain along the bones it
 * bends, with a line to the target it reaches for and to the pole it bends toward.
 */
internal fun DebugLines.Batch.ikOverlay(rig: ModelRig, roots: List<RuntimeNode>, selected: RigPartSelection?) {
    val nodes = roots.nodesByName()
    rig.ikChains().forEach { (bone, chain) ->
        val joints = chain.links(nodes[bone] ?: return@forEach).map { it.globalMatrix.getTranslation() }
        joints.zipWithNext { start, end -> line(start, end, CHAIN_COLOR) }
        rig.ikTargetMatrix(chain.target, nodes::get)?.let { line(joints.last(), it.getTranslation(), REACH_COLOR) }
        if (joints.size > 2) rig.ikTargetMatrix(chain.pole, nodes::get)?.let { line(joints[1], it.getTranslation(), POLE_COLOR) }
    }
    previewIkTargets(rig, roots).forEach { target ->
        val isSelected = selected != null && selected.bone == target.bone && selected.id == target.id
        cross(target.position, if (isSelected) SELECTED_COLOR else TARGET_COLOR)
    }
}

private fun DebugLines.Batch.cross(center: Vec3f, color: Int) {
    line(center - Vec3f(CROSS, 0f, 0f), center + Vec3f(CROSS, 0f, 0f), color)
    line(center - Vec3f(0f, CROSS, 0f), center + Vec3f(0f, CROSS, 0f), color)
    line(center - Vec3f(0f, 0f, CROSS), center + Vec3f(0f, 0f, CROSS), color)
}

private fun List<RuntimeNode>.nodesByName(): Map<String, RuntimeNode> = HashMap<String, RuntimeNode>().also { map ->
    forEach { root -> root.walk().forEach { map.putIfAbsent(it.name, it) } }
}

/** How far from a target's cross, in panel pixels, a click still picks it. */
private const val PICK_RADIUS = 8f

/** Half the size of a target's cross, in model units. */
private const val CROSS = 0.06f

private val TARGET_COLOR = 0xFFB57CFF.toInt()
private val SELECTED_COLOR = 0xFFFFA333.toInt()
private val CHAIN_COLOR = 0xFF4FD1FF.toInt()
private val REACH_COLOR = 0x884FD1FF.toInt()
private val POLE_COLOR = 0x88B57CFF.toInt()
