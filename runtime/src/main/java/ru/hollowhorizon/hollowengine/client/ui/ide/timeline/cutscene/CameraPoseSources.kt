package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene

import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.utils.rl

/**
 * Something outside the cutscene system that wants to point the camera, such as a dialogue's own camera.
 */
interface CameraPoseSource {
    /** Where the camera should be, or null while this source leaves it alone. */
    val currentPose: CameraPose?
}

/**
 * Camera sources addons contribute. They are asked only when no cutscene plays and no pose is held,
 * so a cutscene always wins over them; among themselves the first one with a pose wins.
 */
object CameraPoseSources {
    val point = ExtensionPoints.create("hollowengine:camera/pose-sources".rl, CameraPoseSource::class)

    fun register(id: String, source: CameraPoseSource): ExtensionHandle = point.register(id.rl, source)

    val currentPose: CameraPose? get() = point.extensions.firstNotNullOfOrNull { it.currentPose }
}
