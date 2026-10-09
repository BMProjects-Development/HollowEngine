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
import ru.hollowhorizon.hollowengine.common.models.IkTargetSpec
import ru.hollowhorizon.hollowengine.common.models.withIkTargetRenamed
import ru.hollowhorizon.hollowengine.common.models.RigBoneOrigin
import ru.hollowhorizon.hollowengine.common.models.withAddedBoneRenamed
import ru.hollowhorizon.hollowengine.common.models.withoutAddedBone
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
    val collider = state.selectedPart
    return InspectorTarget(
        id = "rig-bone-${bone ?: ""}",
        title = bone ?: rigText("model"),
        icon = if (bone == null) ModelIcon else BoneIcon,
        subtitle = rigText(if (bone == null) "section_model" else "section_bone"),
    ) {
        CompositionLocalProvider(LocalEditorBones provides bones) {
            key(bone) { HolderFields(document, bone, collider, onSelectBone = state::select) }
        }
    }
}

@Composable
internal fun HolderFields(
    document: RigEditing,
    bone: String?,
    selectedPart: String?,
    kinds: List<RigAttachmentType<*>> = attachableKinds(bone),
    onSelectBone: (String?) -> Unit = {},
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
            val origin = current.origin
            if (origin != null) AddedBoneFields(document, bone, origin, onSelectBone) else PoseFields(document, bone, current)
            val model = LocalRigModelInfo.current
            if (bone in model.meshBones) MeshMaterialFields(document, bone, current, model.materials)
        } else if (current.attachments.isEmpty()) {
            Hint(rigText("model_hint"))
        }

        val targets = document.occupied.allAttachments().mapNotNull { (_, spec) -> (spec as? IkTargetSpec)?.id }
        CompositionLocalProvider(LocalEditorRigTargets provides targets) {
            current.attachments.forEachIndexed { index, attachment ->
                key(index) { AttachmentSection(document, bone, current, index, attachment, attachment.id == selectedPart) }
            }
        }

        AddAttachment(document, bone, current, kinds)
    }
}

/**
 * A bone the rig adds: its name, what it hangs on, and where. A new name is applied by its button, since the
 * name is what the bone is selected and kept by, and renaming it letter by letter would lose the field.
 */
@Composable
private fun AddedBoneFields(document: RigEditing, bone: String, origin: RigBoneOrigin, onSelectBone: (String?) -> Unit) {
    var draft by remember(bone) { mutableStateOf(bone) }
    val path = "/$bone/origin"
    fun write(next: RigBoneOrigin) = document.edit(mergeKey = path) { it.withBone(bone, it.holder(bone).copy(origin = next)) }

    Section(rigText("section_added_bone")) {
        TextRow(rigText("name"), draft, id = "$path/name") { draft = it.trim() }
        if (draft != bone) {
            val taken = draft.isBlank() || draft in LocalEditorBones.current || draft in document.occupied.bones
            if (taken) {
                Hint(rigText("bone_name_taken"))
            } else {
                InspectorButton(rigText("rename_bone"), tags = listOf("primary")) {
                    document.edit { it.withAddedBoneRenamed(bone, draft) }
                    onSelectBone(draft)
                }
            }
        }
        Readonly(rigText("bone_parent"), origin.parent ?: rigText("model"))
        Vec3Row(rigText("pose_position"), origin.offset, "$path/offset") { write(origin.copy(offset = it)) }
        Vec3Row(rigText("pose_rotation"), origin.rotation, "$path/rotation") { write(origin.copy(rotation = it)) }
        InspectorButton(rigText("remove_bone"), tags = listOf("danger")) {
            document.edit { it.withoutAddedBone(bone) }
            onSelectBone(origin.parent)
        }
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
    index: Int,
    attachment: RigAttachmentSpec,
    selected: Boolean,
) {
    val type = RigAttachmentTypes.of(attachment)
    val title = if (type?.namedAcrossRig == true) "${type.title()} · ${attachment.id}" else type.title()
    val path = "/${bone ?: ""}/#$index"

    Section(if (selected) "◆ $title" else title) {
        if (type == null) {
            Hint(rigText("unknown_attachment"))
        } else {
            val colliders = document.occupied.holder(bone).attachments.filterIsInstance<ColliderAttachmentSpec>().map { it.id }
            CompositionLocalProvider(LocalEditorColliders provides colliders) {
                AttachmentFields(type, attachment, path) { changed ->
                    document.edit(mergeKey = path, label = UndoLabel("${UndoLabel.LANG}.rig.attachment", attachment.id)) { rig ->
                        val placed = rig.withHolder(bone, rig.holder(bone).withAttachment(attachment.id, changed))
                        if (attachment is IkTargetSpec && changed.id != attachment.id) placed.withIkTargetRenamed(attachment.id, changed.id)
                        else placed
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
 * A name for a new attachment of [type] on [bone]. Kinds others refer to by name, as scripts do colliders
 * and IK chains do targets, are named across the whole rig.
 */
internal fun freeAttachmentId(rig: ModelRig, bone: String?, type: RigAttachmentType<*>): String {
    val taken = if (type.namedAcrossRig) {
        rig.allAttachments().filter { type.specClass.isInstance(it.second) }.mapTo(HashSet()) { it.second.id }
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

