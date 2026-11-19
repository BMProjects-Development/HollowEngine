package ru.hollowhorizon.hollowengine.common.attachments.components

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.common.scripting.annotations.Attach
import ru.hollowhorizon.hollowengine.api.Syncable
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorAsset
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorIcon
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorModel
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.models.ModelRig

/**
 * Model, that can be attached to the bone or entity.
 *
 * [rig] allows to customize model with special components or decorations.
 */
@Registerable
@Attach(Entity::class)
@Syncable
@Serializable
@EditorIcon("hollowengine:textures/gui/icons/file_model.svg")
@SerialName("hollowengine:model")
data class Model(
    @EditorModel(DEFAULT)
    @EditorAsset("gltf", "glb", "geo.json", "fbx", "obj", "bbmodel")
    val model: String = DEFAULT,
    @EditorHidden
    val rig: ModelRig = ModelRig.EMPTY,
) {
    val isEmpty: Boolean get() = model.isBlank()

    companion object {
        const val DEFAULT = "hollowengine:models/entity/player_model.gltf"
    }
}
