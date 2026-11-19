package ru.hollowhorizon.hollowengine.client.scene

import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import java.util.UUID

internal data class SceneComponentKey(val owner: SceneObject, val id: UUID)
