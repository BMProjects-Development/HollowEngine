package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import ru.hollowhorizon.hollowengine.client.models.internal.v2.RenderModelNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.client.ui.ide.PartIcons
import ru.hollowhorizon.hollowengine.client.ui.ide.PartPath
import ru.hollowhorizon.hollowengine.client.ui.ide.SceneTarget
import ru.hollowhorizon.hollowengine.client.ui.ide.attachmentIcon
import ru.hollowhorizon.hollowengine.client.ui.ide.attachmentLabel
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiMenuParts
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeItem
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.freeBoneName
import ru.hollowhorizon.hollowengine.common.models.withAddedBone
import ru.hollowhorizon.hollowengine.common.models.rigId

/** The id of the row that stands for the model itself, above its bones. */
private const val RigRootId = "rig-root"
private const val PartRowPrefix = '\u001D'

private const val RigIcon = "hollowengine:textures/gui/icons/files/rig.svg"
private const val VisibleIcon = "hollowengine:textures/gui/icons/visible.svg"
private const val InvisibleIcon = "hollowengine:textures/gui/icons/invisible.svg"
private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"

/**
 * The bones as the scene window shows them: the model as the root, its skeleton under it. The eye of
 * a bone hides it in the preview only; whether it is drawn in the game is the inspector's field.
 */
internal fun rigSceneTarget(
    document: RigEditing,
    state: RigEditorState,
    title: String,
    /** Whether the rig can add bones of its own; a montage only hangs things on the ones the model has. */
    addsBones: Boolean = true,
    sceneId: String = "rig-bones",
): SceneTarget {
    val nodes = state.viewer.nodes
    val rig = document.rig
    val selectedPart = state.partSelection?.takeIf { rig.holder(it.bone).attachment(it.id) != null }
    return SceneTarget(
        id = sceneId,
        items = buildList {
            add(
                UiTreeItem(
                    id = RigRootId,
                    label = title,
                    depth = 0,
                    payload = null,
                    icon = RigIcon,
                    hasChildren = nodes.isNotEmpty() || rig.attachments.isNotEmpty(),
                    expanded = state.rootExpanded,
                    selected = state.selected == null && selectedPart == null,
                )
            )
            if (state.rootExpanded) {
                appendAttachments(rig.attachments, null, selectedPart, depth = 1)
                appendBones(nodes, rig, state.expanded, state.selected, selectedPart, depth = 1)
            }
        },
        onSelect = { id ->
            val part = id?.rigPart()
            if (part == null) state.select(id.takeUnless { it == RigRootId })
            else state.select(part.bone, part.id)
        },
        onToggle = { id ->
            when (id) {
                RigRootId -> state.rootExpanded = !state.rootExpanded
                in state.expanded -> state.expanded.remove(id)
                else -> state.expanded.add(id)
            }
        },
        hint = rigText("no_bones").takeIf { nodes.isEmpty() && rig.attachments.isEmpty() },
        menu = { id ->
            val part = id?.rigPart()
            when {
                id == null -> emptyList()
                part == null -> boneMenu(document, state, id.takeUnless { it == RigRootId }, addsBones)
                else -> listOf(UiDropdownItem(rigText("remove_attachment"), mark = UiMenuParts.icon(PartIcons.REMOVE)) {
                    document.edit { it.withHolder(part.bone, it.holder(part.bone).withoutAttachment(part.id)) }
                    if (state.partSelection == part) state.select(part.bone)
                })
            }
        },
        onIconClick = { id -> nodes.findBone(id)?.let(state.viewer::toggleNodeVisibility) },
        history = document.history,
    )
}

private fun MutableList<UiTreeItem<Any?>>.appendBones(
    nodes: List<RenderModelNode>,
    rig: ModelRig,
    expanded: List<String>,
    selected: String?,
    selectedPart: RigPartSelection?,
    depth: Int,
) {
    nodes.forEach { node ->
        val bone = rig.bone(node.name)
        val marks = listOfNotNull(
            bone?.alias?.takeIf { it.isNotBlank() }?.let { "→$it" },
            "+".takeIf { bone?.origin != null },
        ).joinToString(" ")

        add(
            UiTreeItem(
                id = node.name,
                label = if (marks.isEmpty()) node.name else "${node.name}  $marks",
                depth = depth,
                payload = null,
                icon = if (node.isVisible) VisibleIcon else InvisibleIcon,
                hasChildren = node.children.isNotEmpty() || !bone?.attachments.isNullOrEmpty(),
                expanded = node.name in expanded,
                selected = node.name == selected && selectedPart == null,
            )
        )
        if (node.name in expanded) {
            appendAttachments(bone?.attachments.orEmpty(), node.name, selectedPart, depth + 1)
            appendBones(node.children, rig, expanded, selected, selectedPart, depth + 1)
        }
    }
}

private fun MutableList<UiTreeItem<Any?>>.appendAttachments(
    attachments: List<Any>,
    bone: String?,
    selected: RigPartSelection?,
    depth: Int,
) {
    for (spec in attachments) {
        add(UiTreeItem(
            id = PartRowPrefix + PartPath(bone = bone, attachment = spec.rigId).encode(),
            label = attachmentLabel(spec),
            icon = attachmentIcon(spec),
            payload = null,
            depth = depth,
            selected = selected?.bone == bone && selected?.id == spec.rigId,
        ))
    }
}

private fun String.rigPart(): RigPartSelection? {
    if (!startsWith(PartRowPrefix)) return null
    val path = PartPath.decode(substring(1)) ?: return null
    return RigPartSelection(path.bone, path.attachment ?: return null)
}

/**
 * What a right click on a bone offers: adding a bone under it, hanging something on it, and hiding it in the
 * preview. On the model itself, null [bone], a new bone and only what can hang on the whole model.
 */
private fun boneMenu(document: RigEditing, state: RigEditorState, bone: String?, addsBones: Boolean): List<UiDropdownItem> {
    val node = bone?.let { state.viewer.nodes.findBone(it) }
    val kinds = attachableKinds(bone, node?.supportsMeshExtrusion == true).availableOn(document.occupied.holder(bone))
    return listOfNotNull(
        UiDropdownItem(rigText("add_bone"), mark = UiMenuParts.icon(AddIcon)) {
            val existing = state.viewer.nodes.flatMap { it.walk() }.map(RenderModelNode::name)
            var added: String? = null
            document.edit { rig -> rig.freeBoneName(existing).let { name -> added = name; rig.withAddedBone(name, bone) } }
            if (bone != null && bone !in state.expanded) state.expanded += bone
            added?.let(state::select)
        }.takeIf { addsBones },
        UiDropdownItem(
            rigText("attach"),
            mark = UiMenuParts.icon(AddIcon),
            children = kinds.map { type ->
                val create = requireNotNull(type.createDefault)
                UiDropdownItem(type.title()) {
                    var added: Any? = null
                    document.edit { rig ->
                        val spec = create(freeAttachmentId(document.occupied, bone, type)).also { added = it }
                        rig.withHolder(bone, rig.holder(bone).withAttachment(spec))
                    }
                    state.select(bone, added?.rigId)
                }
            },
        ).takeIf { kinds.isNotEmpty() },
        node?.let {
            UiDropdownItem(
                rigText(if (it.isVisible) "hide_in_preview" else "show_in_preview"),
                mark = UiMenuParts.icon(if (it.isVisible) InvisibleIcon else VisibleIcon),
                separatorBefore = kinds.isNotEmpty(),
            ) { state.viewer.toggleNodeVisibility(it) }
        },
    )
}

private fun List<RenderModelNode>.findBone(name: String): RenderModelNode? =
    firstNotNullOfOrNull { root -> root.walk().firstOrNull { it.name == name } }
