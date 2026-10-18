package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonObject
import ru.hollowhorizon.hollowengine.client.ui.inspector.AutoFields
import ru.hollowhorizon.hollowengine.client.ui.inspector.ComponentJson
import ru.hollowhorizon.hollowengine.common.decals.DecalSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxDecalSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty

/** How strong the decal of a node is and what it is tinted, then the decal itself, the same as any decal. */
@Composable
internal fun DecalFields(document: VfxEditing, state: VfxInspectorState, node: VfxDecalSpec) {
    Folding(state, "decal", vfxText("section_decal"), VfxIcons.DECAL) {
        VfxValueRow(vfxText("decal_opacity"), node.opacity, VfxProperty.DECAL_OPACITY, vfxText("decal_opacity_hint")) {
            document.replace(node.copy(opacity = it))
        }
        VfxColorRow(vfxText("tint"), node.tint, VfxProperty.TINT) { document.replace(node.copy(tint = it)) }
        val format = ComponentJson.format
        val json = format.encodeToJsonElement(DecalSpec.serializer(), node.decal) as JsonObject
        AutoFields(null, DecalSpec.serializer().descriptor, json, "/vfx-decal-${node.id}") { edited ->
            runCatching { format.decodeFromJsonElement(DecalSpec.serializer(), edited) }.getOrNull()
                ?.let { document.replace(node.copy(decal = it)) }
        }
    }
}
