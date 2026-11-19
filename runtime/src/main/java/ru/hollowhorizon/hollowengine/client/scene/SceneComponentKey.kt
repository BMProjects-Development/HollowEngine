package ru.hollowhorizon.hollowengine.client.scene

import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import java.util.UUID

/** Identifies one binding, including when the same ID occurs on several nodes of a prefab. */
data class SceneComponentKey(val owner: SceneObject, val id: UUID) {
    val value: Any? get() = owner.component(id)
}
