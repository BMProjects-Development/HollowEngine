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

    /** One way to fill in a rig: what the editor's menu calls it, the icon beside it, and the generator itself. */
    class Entry(val id: String, val titleKey: String, val icon: String, val generator: RigGenerator)

    const val DEFAULT_ICON = "hollowengine:textures/gui/icons/rig/generate.svg"

    init {
        register(
            ColliderRigGenerator.ID,
            "hollowengine.gui.rig_editor.generate_colliders",
            ColliderRigGenerator,
            icon = "hollowengine:textures/gui/icons/rig/colliders.svg",
        )
    }

    fun register(id: String, titleKey: String, generator: RigGenerator, icon: String = DEFAULT_ICON): ExtensionHandle =
        point.register(id.rl, Entry(id, titleKey, icon, generator))

    val all: List<Entry> get() = point.extensions
}
