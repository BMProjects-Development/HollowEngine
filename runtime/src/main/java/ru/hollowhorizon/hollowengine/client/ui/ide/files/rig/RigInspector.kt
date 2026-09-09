package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import androidx.compose.runtime.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.entity.ComponentFields
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeRigDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentType
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentTypes
import ru.hollowhorizon.hollowengine.common.models.RigBone

private const val FieldStylesheet = "hollowengine:ui/styles/entity-editor.hss"

/**
 * What is authored onto the selected bone: its alias, whether it is drawn, and everything hung on it.
 */
@Composable
internal fun RigInspector(document: HollowIdeRigDocument, bone: String, modifier: Modifier) {
    Column(
        modifier = modifier.background(AnimatorColors.Panel).border(1.px, AnimatorColors.Border).style(FieldStylesheet)
            .padding(10.px).gap(8.px).scrollable(horizontal = false),
    ) {
        Text(rigText("parameters"), modifier = Modifier.fontSize(11f).foreground(AnimatorColors.Muted))

        key(bone) { BoneFields(document, bone) }
    }
}

@Composable
private fun BoneFields(document: HollowIdeRigDocument, bone: String) {
    val current = document.rig.bone(bone) ?: RigBone.EMPTY

    Column(tags = listOf("ee-bone-fields"), modifier = Modifier.size(100.percent, UiLength.Fit).gap(8.px)) {
        Section(rigText("section_bone")) {
            Readonly(rigText("name"), bone)
            TextRow(rigText("alias"), current.alias.orEmpty()) { value ->
                document.edit { it.withBone(bone, current.copy(alias = value.trim().ifBlank { null })) }
            }
            PillRows(listOf(false, true), current.hidden, { rigText(if (it) "hidden" else "visible") }) { hidden ->
                document.edit { it.withBone(bone, current.copy(hidden = hidden)) }
            }
        }

        current.attachments.forEach { attachment ->
            key(attachment.id) { AttachmentSection(document, bone, current, attachment) }
        }

        AddAttachment(document, bone, current)
    }
}

@Composable
private fun AttachmentSection(
    document: HollowIdeRigDocument,
    bone: String,
    current: RigBone,
    attachment: RigAttachmentSpec,
) {
    val type = RigAttachmentTypes.of(attachment)

    Section(type.title()) {
        if (type == null) {
            Hint(rigText("unknown_attachment"))
        } else {
            AttachmentFields(type, attachment, "/$bone/${attachment.id}") { changed ->
                document.edit { it.withBone(bone, current.withAttachment(attachment.id, changed)) }
            }
        }

        AnimatorButton(
            rigText("remove_attachment"),
            modifier = Modifier.size(100.percent, 20.px),
            color = AnimatorColors.Danger,
        ) {
            document.edit { it.withBone(bone, current.withoutAttachment(attachment.id)) }
        }
    }
}

/**
 * The attachment's own fields, read straight off how it is serialized.
 */
@Composable
private fun AttachmentFields(
    type: RigAttachmentType<*>,
    attachment: RigAttachmentSpec,
    path: String,
    onChange: (RigAttachmentSpec) -> Unit,
) {
    @Suppress("UNCHECKED_CAST") val serializer = type.serializer as KSerializer<RigAttachmentSpec>
    val encoded = remember(attachment) {
        runCatching {
            AttachmentJson.encodeToJsonElement(
                serializer,
                attachment
            ) as JsonObject
        }.onFailure { HollowEngine.LOGGER.warn("Could not show attachment '{}': {}", type.id, it.message) }.getOrNull()
    } ?: return Hint(rigText("no_attachment_editor"))

    ComponentFields(
        owner = null,
        descriptor = serializer.descriptor,
        value = encoded,
        path = path,
    ) { updated ->
        runCatching { AttachmentJson.decodeFromJsonElement(serializer, updated) }.onSuccess(onChange)
            .onFailure { HollowEngine.LOGGER.warn("Could not apply an edit to '{}': {}", type.id, it.message) }
    }
}

@Composable
private fun AddAttachment(document: HollowIdeRigDocument, bone: String, current: RigBone) {
    var open by remember(bone) { mutableStateOf(false) }
    var anchor by remember(bone) { mutableStateOf(UiRect.Zero) }
    val kinds = RigAttachmentTypes.all.filter { it.createDefault != null }
    if (kinds.isEmpty()) return

    AnimatorButton(
        rigText("add_attachment"),
        modifier = Modifier.size(100.percent, 22.px).onPlaced { anchor = it },
        color = AnimatorColors.Accent,
    ) { open = true }

    if (!open) return

    ContextMenu(
        id = "rig-add-attachment",
        anchorBounds = anchor,
        items = kinds.map { type ->
            val create = requireNotNull(type.createDefault)
            UiDropdownItem(type.title()) {
                document.edit { it.withBone(bone, current.withAttachment(create(freeId(current, type)))) }
            }
        },
        onExpandedChange = { if (!it) open = false },
    )
}

private fun freeId(bone: RigBone, type: RigAttachmentType<*>): String {
    val base = type.id.substringAfterLast('/')
    if (bone.attachment(base) == null) return base

    var index = 2
    while (bone.attachment("$base$index") != null) index++
    return "$base$index"
}

private fun RigAttachmentType<*>?.title(): String {
    if (this == null) return rigText("unknown_kind")
    val translated = titleKey.lang
    return if (translated == titleKey) id.substringAfterLast('/') else translated
}

private val AttachmentJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

