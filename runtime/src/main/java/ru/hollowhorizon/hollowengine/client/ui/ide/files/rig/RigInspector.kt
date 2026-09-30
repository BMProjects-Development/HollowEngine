package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import androidx.compose.runtime.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
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
import ru.hollowhorizon.hollowengine.common.models.HitboxAttachmentSpec


private const val BoneIcon = "hollowengine:textures/gui/icons/graph.svg"

/**
 * What is authored onto the model (null) or selected bone.
 *
 * The bone list the picker offers travels with the target, because the shared inspector composes it
 * somewhere else entirely and knows nothing about this rig.
 */
internal fun rigInspectorTarget(
    document: HollowIdeRigDocument,
    bone: String?,
    bones: List<String>,
    attachmentTypes: Set<String>? = null,
): InspectorTarget = InspectorTarget(
    id = "rig-${bone?.let { "bone-$it" } ?: "model"}-$attachmentTypes",
    title = bone ?: rigText("model_root"),
    icon = BoneIcon,
    subtitle = rigText(if (bone == null) "section_model" else "section_bone"),
) {
    CompositionLocalProvider(LocalEditorBones provides bones) {
        key(bone) { BoneFields(document, bone, attachmentTypes) }
    }
}

@Composable
internal fun BoneFields(document: HollowIdeRigDocument, bone: String?, attachmentTypes: Set<String>? = null) {
    val current = document.rig.attachmentTarget(bone)
    // Other attachment kinds currently require a bone; model-space hitboxes do not.
    val types = if (bone == null) setOf(HitboxAttachmentSpec.TYPE_ID).let {
        if (attachmentTypes == null) it else it.intersect(attachmentTypes)
    } else attachmentTypes

    Column(tags = listOf("insp-body")) {
        if (bone == null) Hint(rigText("model_hitboxes_hint"))
        if (bone != null && attachmentTypes == null) Section(rigText("section_bone")) {
            Readonly(rigText("name"), bone)
            TextRow(rigText("alias"), current.alias.orEmpty()) { value ->
                document.edit { it.withBone(bone, current.copy(alias = value.trim().ifBlank { null })) }
            }
            Pills(listOf(false, true), current.hidden, { rigText(if (it) "hidden" else "visible") }) { hidden ->
                document.edit { it.withBone(bone, current.copy(hidden = hidden)) }
            }
        }

        current.attachments.filter { types == null || RigAttachmentTypes.of(it)?.id in types }.forEach { attachment ->
            key(attachment.id) { AttachmentSection(document, bone, current, attachment) }
        }

        AddAttachment(document, bone, types)
    }
}

@Composable
private fun AttachmentSection(
    document: HollowIdeRigDocument,
    bone: String?,
    current: RigBone,
    attachment: RigAttachmentSpec,
) {
    val type = RigAttachmentTypes.of(attachment)
    var invalid by remember(attachment) { mutableStateOf(false) }

    Section("${type.title()} · ${attachment.id}") {
        if (type == null) {
            Hint(rigText("unknown_attachment"))
        } else {
            AttachmentFields(type, attachment, "/$bone/${attachment.id}") { changed ->
                invalid = changed.id != attachment.id && current.attachment(changed.id) != null
                if (invalid) return@AttachmentFields
                document.edit { rig -> rig.editAttachmentTarget(bone) { it.withAttachment(attachment.id, changed) } }
            }
            if (invalid) Hint(rigText("duplicate_attachment"))
        }

        InspectorButton(rigText("remove_attachment"), tags = listOf("danger")) {
            document.edit { rig -> rig.editAttachmentTarget(bone) { it.withoutAttachment(attachment.id) } }
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
    var invalid by remember(attachment) { mutableStateOf(false) }
    val encoded = remember(attachment) {
        runCatching {
            AttachmentJson.encodeToJsonElement(
                serializer,
                attachment
            ) as JsonObject
        }.onFailure { HollowEngine.LOGGER.warn("Could not show attachment '{}': {}", type.id, it.message) }.getOrNull()
    } ?: return Hint(rigText("no_attachment_editor"))

    AutoFields(
        owner = null,
        descriptor = serializer.descriptor,
        value = encoded,
        path = path,
    ) { updated ->
        runCatching { AttachmentJson.decodeFromJsonElement(serializer, updated) }.onSuccess {
            invalid = false
            onChange(it)
        }.onFailure { invalid = true }
    }
    if (invalid) Hint(rigText("invalid_attachment"))
}

@Composable
private fun AddAttachment(document: HollowIdeRigDocument, bone: String?, attachmentTypes: Set<String>?) {
    var open by remember(bone) { mutableStateOf(false) }
    var anchor by remember(bone) { mutableStateOf(UiRect.Zero) }
    val kinds = RigAttachmentTypes.all.filter { it.createDefault != null && (attachmentTypes == null || it.id in attachmentTypes) }
    if (kinds.isEmpty()) return

    fun add(type: RigAttachmentType<*>) {
        val create = requireNotNull(type.createDefault)
        document.edit { rig ->
            rig.editAttachmentTarget(bone) { latest -> latest.withAttachment(create(freeId(latest, type))) }
        }
    }

    InspectorButton(
        rigText(if (attachmentTypes == setOf(HitboxAttachmentSpec.TYPE_ID)) "add_hitbox" else "add_attachment"),
        icon = "hollowengine:textures/gui/icons/add.svg",
        modifier = Modifier.onPlaced { anchor = it },
        tags = listOf("primary"),
    ) { if (kinds.size == 1) add(kinds.single()) else open = true }

    if (!open) return

    ContextMenu(
        id = "rig-add-attachment",
        anchorBounds = anchor,
        items = kinds.map { type ->
            UiDropdownItem(type.title()) { add(type) }
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

