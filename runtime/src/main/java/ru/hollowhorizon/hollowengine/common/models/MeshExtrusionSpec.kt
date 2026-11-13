package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorNotAnimated
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRange

private const val TYPE_ID = "hollowengine:rig/mesh_extrusion"
private const val DEFAULT_THICKNESS = 1.0f / 32.0f
private const val MAX_THICKNESS = 1.0

/** Extrudes the visible texels along the surface normals, or against them when [thickness] is negative. */
@Serializable
@SerialName(TYPE_ID)
data class MeshExtrusionSpec(
    @EditorHidden override val id: String = "mesh_extrusion",
    @EditorName("hollowengine.gui.rig_editor.extrusion_thickness")
    @EditorDescription("hollowengine.gui.rig_editor.extrusion_thickness_hint")
    @EditorRange(-MAX_THICKNESS, MAX_THICKNESS)
    @EditorNotAnimated
    val thickness: Float = DEFAULT_THICKNESS,
) : RigAttachmentSpec() {
    init {
        require(thickness in -MAX_THICKNESS.toFloat()..MAX_THICKNESS.toFloat()) {
            "Invalid mesh extrusion thickness: $thickness"
        }
    }

    override fun withId(id: String) = copy(id = id)

    override fun structure() = copy(thickness = DEFAULT_THICKNESS)

    companion object {
        val TYPE = RigAttachmentType(
            id = TYPE_ID,
            specClass = MeshExtrusionSpec::class,
            serializer = serializer(),
            titleKey = "hollowengine.gui.rig_editor.kind_mesh_extrusion",
            createDefault = { id -> MeshExtrusionSpec(id) },
            editorColor = 0x60B6C9,
            requiresMesh = true,
            uniqueOnHolder = true,
        )
    }
}
