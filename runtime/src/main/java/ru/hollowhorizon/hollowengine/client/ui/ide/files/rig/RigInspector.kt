package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import androidx.compose.runtime.*
import kotlinx.serialization.KSerializer
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
import ru.hollowhorizon.hollowengine.common.colliders.ColliderAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentType
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentTypes
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.models.RigPose
import ru.hollowhorizon.hollowengine.common.utils.math.eulerDegreesXyz
import ru.hollowhorizon.hollowengine.common.utils.math.eulerRotationXyz
import ru.hollowhorizon.hollowengine.client.history.UndoLabel


private const val BoneIcon = "hollowengine:textures/gui/icons/graph.svg"
private const val ModelIcon = "hollowengine:textures/gui/icons/files/rig.svg"

/**
 * What is authored onto the selected bone, or onto the model itself when no bone is selected: the bone's
 * alias and whether it is drawn, and everything hung on it.
 *
 * The bone list the picker offers travels with the target, because the shared inspector composes it
 * somewhere else entirely and knows nothing about this rig.
 */
internal fun rigInspectorTarget(
    document: RigEditing,
    state: RigEditorState,
    bones: List<String>,
): InspectorTarget {
    val bone = state.selected
    val collider = state.selectedCollider
    return InspectorTarget(
        id = "rig-bone-${bone ?: ""}",
        title = bone ?: rigText("model"),
        icon = if (bone == null) ModelIcon else BoneIcon,
        subtitle = rigText(if (bone == null) "section_model" else "section_bone"),
    ) {
        CompositionLocalProvider(LocalEditorBones provides bones) {
            key(bone) { HolderFields(document, bone, collider) }
        }
    }
}

@Composable
internal fun HolderFields(
    document: RigEditing,
    bone: String?,
    selectedCollider: String?,
    kinds: List<RigAttachmentType<*>> = attachableKinds(bone),
) {
    val current = document.rig.holder(bone)

    Column(tags = listOf("insp-body")) {
        if (bone != null) {
            Section(rigText("section_bone")) {
                Readonly(rigText("name"), bone)
                TextRow(rigText("alias"), current.alias.orEmpty()) { value ->
                    document.edit { it.withBone(bone, current.copy(alias = value.trim().ifBlank { null })) }
                }
                Pills(listOf(false, true), current.hidden, { rigText(if (it) "hidden" else "visible") }) { hidden ->
                    document.edit { it.withBone(bone, current.copy(hidden = hidden)) }
                }
            }
            PoseFields(document, bone, current)
            val model = LocalRigModelInfo.current
            if (bone in model.meshBones) MeshMaterialFields(document, bone, current, model.materials)
        } else if (current.attachments.isEmpty()) {
            Hint(rigText("model_hint"))
        }

        current.attachments.forEach { attachment ->
            key(attachment.id) { AttachmentSection(document, bone, current, attachment, attachment.id == selectedCollider) }
        }

        AddAttachment(document, bone, current, kinds)
    }
}

/** Where the bone is moved over its animation; rotation is shown in degrees. */
@Composable
private fun PoseFields(document: RigEditing, bone: String, current: RigBone) {
    val pose = current.pose ?: RigPose.IDENTITY
    val path = "/$bone/pose"
    fun write(next: RigPose) = document.edit(mergeKey = path) { it.withBone(bone, current.copy(pose = next.takeUnless(RigPose::isIdentity))) }

    Section(rigText("section_pose")) {
        Vec3Row(rigText("pose_position"), pose.position, "$path/position") { write(pose.copy(position = it)) }
        Vec3Row(rigText("pose_rotation"), pose.rotation.eulerDegreesXyz(), "$path/rotation") { degrees ->
            write(pose.copy(rotation = eulerRotationXyz(degrees)))
        }
        Vec3Row(rigText("pose_scale"), pose.scale, "$path/scale") { write(pose.copy(scale = it)) }
        if (!pose.isIdentity) InspectorButton(rigText("pose_reset")) { write(RigPose.IDENTITY) }
    }
}

@Composable
private fun AttachmentSection(
    document: RigEditing,
    bone: String?,
    current: RigBone,
    attachment: RigAttachmentSpec,
    selected: Boolean,
) {
    val type = RigAttachmentTypes.of(attachment)
    val title = if (attachment is ColliderAttachmentSpec) "${type.title()} · ${attachment.id}" else type.title()

    Section(if (selected) "◆ $title" else title) {
        if (type == null) {
            Hint(rigText("unknown_attachment"))
        } else {
            val colliders = document.occupied.holder(bone).attachments.filterIsInstance<ColliderAttachmentSpec>().map { it.id }
            CompositionLocalProvider(LocalEditorColliders provides colliders) {
                AttachmentFields(type, attachment, "/${bone ?: ""}/${attachment.id}") { changed ->
                    document.edit(mergeKey = "/${bone ?: ""}/${attachment.id}", label = UndoLabel("${UndoLabel.LANG}.rig.attachment", attachment.id)) {
                        it.withHolder(bone, current.withAttachment(attachment.id, changed))
                    }
                }
            }
        }

        InspectorButton(rigText("remove_attachment"), tags = listOf("danger")) {
            document.edit { it.withHolder(bone, current.withoutAttachment(attachment.id)) }
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
            ComponentJson.format.encodeToJsonElement(
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
        runCatching { ComponentJson.format.decodeFromJsonElement(serializer, updated) }.onSuccess(onChange)
            .onFailure { HollowEngine.LOGGER.warn("Could not apply an edit to '{}': {}", type.id, it.message) }
    }
}

@Composable
private fun AddAttachment(document: RigEditing, bone: String?, current: RigBone, kinds: List<RigAttachmentType<*>>) {
    var open by remember(bone) { mutableStateOf(false) }
    var anchor by remember(bone) { mutableStateOf(UiRect.Zero) }
    if (kinds.isEmpty()) return

    InspectorButton(
        rigText("attach"),
        icon = "hollowengine:textures/gui/icons/add.svg",
        modifier = Modifier.onPlaced { anchor = it },
        tags = listOf("primary"),
    ) { open = true }

    if (!open) return

    ContextMenu(
        id = "rig-add-attachment",
        anchorBounds = anchor,
        items = kinds.map { type ->
            val create = requireNotNull(type.createDefault)
            UiDropdownItem(type.title()) {
                document.edit { it.withHolder(bone, current.withAttachment(create(freeAttachmentId(document.occupied, bone, type)))) }
            }
        },
        onExpandedChange = { if (!it) open = false },
    )
}

/** What can be hung on [bone]; on the model itself, only what works in model space. */
internal fun attachableKinds(bone: String?): List<RigAttachmentType<*>> =
    RigAttachmentTypes.all.filter { it.createDefault != null && (bone != null || it.allowedOnModel) }

/**
 * A name for a new attachment of [type] on [bone]. Colliders are named across the whole rig, since that
 * name is how scripts and events tell them apart.
 */
internal fun freeAttachmentId(rig: ModelRig, bone: String?, type: RigAttachmentType<*>): String {
    val taken = if (type.specClass == ColliderAttachmentSpec::class) {
        rig.allAttachments().filter { it.second is ColliderAttachmentSpec }.mapTo(HashSet()) { it.second.id }
    } else {
        rig.holder(bone).attachments.mapTo(HashSet()) { it.id }
    }
    val base = type.id.substringAfterLast('/')
    if (base !in taken) return base

    var index = 2
    while ("$base$index" in taken) index++
    return "$base$index"
}

internal fun RigAttachmentType<*>?.title(): String {
    if (this == null) return rigText("unknown_kind")
    val translated = titleKey.lang
    return if (translated == titleKey) id.substringAfterLast('/') else translated
}

