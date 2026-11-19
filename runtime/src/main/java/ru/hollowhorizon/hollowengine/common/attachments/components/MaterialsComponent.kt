package ru.hollowhorizon.hollowengine.common.attachments.components

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.common.scripting.annotations.Attach
import ru.hollowhorizon.hollowengine.api.Syncable
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.models.MaterialSource

/**
 * What this entity looks like, per named material.
 */
@Registerable
@Attach(Entity::class)
@Syncable
@Serializable
@SerialName("hollowengine:materials")
data class MaterialsComponent(
    val materials: Map<String, MaterialSource> = emptyMap(),
) {
    companion object {
        /** The player's own skin, and the material a humanoid model names for it. */
        const val SKIN = "skin"

        /** The player's cape. */
        const val CAPE = "cape"

        /** The player's elytra. */
        const val ELYTRA = "elytra"
    }
}

fun MaterialsComponent.with(name: String, source: MaterialSource): MaterialsComponent =
    copy(materials = materials + (name to source))

fun MaterialsComponent.without(name: String): MaterialsComponent =
    if (name in materials) copy(materials = materials - name) else this
