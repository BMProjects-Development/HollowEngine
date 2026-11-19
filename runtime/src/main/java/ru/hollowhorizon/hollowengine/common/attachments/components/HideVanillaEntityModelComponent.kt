package ru.hollowhorizon.hollowengine.common.attachments.components

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.common.scripting.annotations.Attach
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.api.Syncable

@Registerable
@Attach(Entity::class)
@Syncable
@Serializable
@SerialName("hollowengine:hide_vanilla_entity_model")
data class HideVanillaEntityModelComponent(
    val enabled: Boolean = true,
)
