package ru.hollowhorizon.hollowengine.client.models.internal.rig

import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.utils.rl

fun interface RigGenerator {
    /** [current] is what the editor has now, so a generator can leave what was authored by hand alone. */
    fun generate(model: ModelAttachment, current: ModelRig): ModelRig
}

/**
 * Who can fill in a rig, and what the editor calls each of them.
 */
object RigGenerators {
    val point = ExtensionPoints.create<Entry>("hollowengine:rig/generators".rl)

    class Entry(val id: String, val titleKey: String, val generator: RigGenerator)

    fun register(id: String, titleKey: String, generator: RigGenerator): ExtensionHandle =
        point.register(id.rl, Entry(id, titleKey, generator))

    val all: List<Entry> get() = point.extensions
}
