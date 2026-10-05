package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import ru.hollowhorizon.hollowengine.client.vfx.VfxNodeRuntime
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty

/**
 * What the timeline holds for the properties of [nodeId], as the preview plays it.
 */
internal fun vfxDrivenLookup(
    document: VfxEditing,
    state: VfxEditorState,
    nodeId: String,
): (VfxProperty) -> VfxDrivenValue? = lookup@{ property ->
    val preview = state.preview
    val track = document.effect.timeline.track(nodeId, property) ?: return@lookup null
    val channels = track.curves.filter { it.visible && it.keys.isNotEmpty() }.map { it.channel }.toSet()
    if (channels.isEmpty()) return@lookup null

    preview.time
    preview.revision
    val values = currentValues(preview.instance?.node(nodeId), property)
    val session = state.session
    val record = if (!session.timeline.isRecording) null else { changes: Map<Int, Float> ->
        session.record(nodeId, property, changes, values)
    }
    VfxDrivenValue(values, channels, record)
}

/** The values the running node has for [property] this frame. */
private fun currentValues(node: VfxNodeRuntime?, property: VfxProperty): FloatArray {
    if (node == null) return FloatArray(4)
    return when (property) {
        VfxProperty.POSITION -> node.transform.position.asArray()
        VfxProperty.ROTATION -> node.transform.rotation.asArray()
        VfxProperty.SCALE -> node.transform.scale.asArray()
        VfxProperty.ENABLED -> floatArrayOf(if (node.enabled) 1f else 0f)
        else -> node.drive(property)?.values?.copyOf() ?: FloatArray(4)
    }
}

private fun Vec3f.asArray() = floatArrayOf(x, y, z)
