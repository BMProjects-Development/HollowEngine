package ru.hollowhorizon.hollowengine.common.attachments.components

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.common.scripting.annotations.Attach
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.api.Syncable

/** Arm shape of a vanilla player model: three pixels wide, or four. */
@Serializable
enum class PlayerArms { WIDE, SLIM }

@Registerable
@Attach(Entity::class)
@Syncable
@Serializable
@SerialName("hollowengine:player_arms")
data class PlayerArmsComponent(
    val arms: PlayerArms = PlayerArms.WIDE,
)
