package ru.hollowhorizon.hollowengine.common.attachments.components

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.api.Syncable
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorAsset
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorIcon
import ru.hollowhorizon.hollowengine.common.models.ModelRig

/**
 * Which model an entity or node shows, and what this entity alone hangs on it: [rig] is laid over the
 * model's own rig file, so an effect on one lamp's bulb does not light every lamp. A blank [model] shows
 * nothing and only carries what hangs on the model itself.
 */
@Registerable
@Syncable
@Serializable
@EditorIcon("hollowengine:textures/gui/icons/file_model.svg")
@SerialName("hollowengine:model")
data class Model(
    @EditorAsset(
        "gltf", "glb", "geo.json", "fbx", "obj", "bbmodel"
    ) val model: String = "hollowengine:models/entity/player_model.gltf",
    @EditorHidden
    val rig: ModelRig = ModelRig.EMPTY,
) {
    val isEmpty: Boolean get() = model.isBlank()
}
