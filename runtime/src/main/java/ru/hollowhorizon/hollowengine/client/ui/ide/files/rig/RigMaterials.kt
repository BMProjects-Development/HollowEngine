package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import ru.hollowhorizon.hollowengine.client.ui.inspector.AutoFields
import ru.hollowhorizon.hollowengine.client.ui.inspector.ComponentJson
import ru.hollowhorizon.hollowengine.client.ui.inspector.Hint
import ru.hollowhorizon.hollowengine.client.ui.inspector.Pill
import ru.hollowhorizon.hollowengine.client.ui.inspector.PillFlow
import ru.hollowhorizon.hollowengine.client.ui.inspector.Section
import ru.hollowhorizon.hollowengine.common.models.MaterialSource
import ru.hollowhorizon.hollowengine.common.models.RigBone

/** What the inspector knows of the model a rig dresses: the names of its materials and which bones carry meshes. */
data class RigModelInfo(val materials: List<String> = emptyList(), val meshBones: Set<String> = emptySet())

val LocalRigModelInfo = staticCompositionLocalOf { RigModelInfo() }

/** Which material the meshes on [bone] are drawn with: the model's own, another of its materials, or a new one. */
@Composable
internal fun MeshMaterialFields(document: RigEditing, bone: String, current: RigBone, materials: List<String>) {
    fun assign(name: String?) = document.edit { it.withBone(bone, current.copy(material = name)) }

    Section(rigText("section_material")) {
        PillFlow {
            Pill(rigText("material_default"), active = current.material == null) { assign(null) }
            materials.forEach { name -> Pill(name, active = current.material == name) { assign(name) } }
            Pill(rigText("material_new"), active = false) {
                assign(freeMaterialName(bone, materials + document.occupied.materials.keys))
            }
        }
        Hint(rigText("material_hint"))
    }
}

/** What the material [name] looks like on this model; empty keeps how the model authored it. */
@Composable
internal fun MaterialFields(document: RigEditing, name: String) {
    val source = document.rig.materials[name]
    val encoded = remember(source) { ComponentJson.format.encodeToJsonElement(MaterialSlot.serializer(), MaterialSlot(source)) as JsonObject }

    Section(name) {
        AutoFields(owner = null, descriptor = MaterialSlot.serializer().descriptor, value = encoded, path = "/materials/$name") { updated ->
            val slot = runCatching { ComponentJson.format.decodeFromJsonElement(MaterialSlot.serializer(), updated) }.getOrNull() ?: return@AutoFields
            document.edit(mergeKey = "/materials/$name") { rig ->
                val next = slot.source
                rig.copy(materials = if (next == null) rig.materials - name else rig.materials + (name to next))
            }
        }
        if (source == null) Hint(rigText("material_authored"))
    }
}

/** One material's look, as the inspector edits it. */
@Serializable
private data class MaterialSlot(val source: MaterialSource? = null)

private fun freeMaterialName(bone: String, taken: Collection<String>): String {
    val base = "${bone}_material"
    if (base !in taken) return base
    var index = 2
    while ("$base$index" in taken) index++
    return "$base$index"
}
