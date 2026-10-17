package ru.hollowhorizon.hollowengine.client.models.internal

import ru.hollowhorizon.hollowengine.common.models.ModelMetadata
import java.util.*

/**
 * Every material the model draws with, whether it is listed up front or only reached through a mesh.
 */
fun Model.allMaterials(): List<Material> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Material, Boolean>())
    val result = ArrayList<Material>()

    fun add(material: Material) {
        if (seen.add(material)) result += material
    }

    materials.forEach(::add)
    walkNodes().mapNotNull { it.mesh }.flatMap { it.primitives }.map { it.material }.forEach(::add)
    return result
}

/**
 * Applies the names the model's `.hemeta` gives its materials.
 */
fun Model.renameMaterials(metadata: ModelMetadata) {
    if (metadata.materials.isEmpty()) return
    allMaterials().forEach { material ->
        metadata.renameOf(material.name)?.let { material.name = it }
    }
}
