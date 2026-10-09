package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.client.ui.ide.SceneTarget
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeRigDocument
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeItem
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.freeBoneName
import ru.hollowhorizon.hollowengine.common.models.withAddedBone

/** The id of the row that stands for the model itself, above its bones. */
private const val RigRootId = "rig-root"

private const val RigIcon = "hollowengine:textures/gui/icons/files/rig.svg"
private const val VisibleIcon = "hollowengine:textures/gui/icons/visible.svg"
private const val InvisibleIcon = "hollowengine:textures/gui/icons/invisible.svg"
private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"

/**
 * The bones as the scene window shows them: the model as the root, its skeleton under it. The eye of
 * a bone hides it in the preview only; whether it is drawn in the game is the inspector's field.
 */
internal fun rigSceneTarget(document: HollowIdeRigDocument, state: RigEditorState, title: String): SceneTarget {
    val nodes = state.viewer.nodes
    return SceneTarget(
        id = "rig-bones",
        items = buildList {
            add(
                UiTreeItem(
                    id = RigRootId,
                    label = document.rig.attachments.size.takeIf { it > 0 }?.let { "$title  ●$it" } ?: title,
                    depth = 0,
                    payload = null,
                    icon = RigIcon,
                    hasChildren = nodes.isNotEmpty(),
                    expanded = state.rootExpanded,
                    selected = state.selected == null,
                )
            )
            if (state.rootExpanded) appendBones(nodes, document.rig, state.expanded, state.selected, depth = 1)
        },
        onSelect = { id -> state.select(id.takeUnless { it == RigRootId }) },
        onToggle = { id ->
            when (id) {
                RigRootId -> state.rootExpanded = !state.rootExpanded
                in state.expanded -> state.expanded.remove(id)
                else -> state.expanded.add(id)
            }
        },
        hint = rigText("no_bones").takeIf { nodes.isEmpty() },
        menu = { id -> if (id == null) emptyList() else boneMenu(document, state, id.takeUnless { it == RigRootId }) },
        onIconClick = { id -> nodes.findBone(id)?.let(state.viewer::toggleNodeVisibility) },
        history = document.history,
    )
}

private fun MutableList<UiTreeItem<Any?>>.appendBones(
    nodes: List<RuntimeNode>,
    rig: ModelRig,
    expanded: List<String>,
    selected: String?,
    depth: Int,
) {
    nodes.forEach { node ->
        val bone = rig.bone(node.name)
        val marks = listOfNotNull(
            bone?.attachments?.size?.takeIf { it > 0 }?.let { "●$it" },
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
                hasChildren = node.children.isNotEmpty(),
                expanded = node.name in expanded,
                selected = node.name == selected,
            )
        )
        if (node.children.isNotEmpty() && node.name in expanded) {
            appendBones(node.children, rig, expanded, selected, depth + 1)
        }
    }
}

/**
 * What a right click on a bone offers: adding a bone under it, hanging something on it, and hiding it in the
 * preview. On the model itself, null [bone], a new bone and only what can hang on the whole model.
 */
private fun boneMenu(document: HollowIdeRigDocument, state: RigEditorState, bone: String?): List<UiDropdownItem> {
    val node = bone?.let { state.viewer.nodes.findBone(it) }
    val kinds = attachableKinds(bone)
    return listOfNotNull(
        UiDropdownItem(rigText("add_bone"), icon = AddIcon) {
            val existing = state.viewer.nodes.flatMap { it.walk() }.map(RuntimeNode::name)
            var added: String? = null
            document.edit { rig -> rig.freeBoneName(existing).let { name -> added = name; rig.withAddedBone(name, bone) } }
            if (bone != null && bone !in state.expanded) state.expanded += bone
            added?.let(state::select)
        },
        UiDropdownItem(
            rigText("attach"),
            icon = AddIcon,
            children = kinds.map { type ->
                val create = requireNotNull(type.createDefault)
                UiDropdownItem(type.title()) {
                    var added: RigAttachmentSpec? = null
                    document.edit { rig ->
                        val spec = create(freeAttachmentId(rig, bone, type)).also { added = it }
                        rig.withHolder(bone, rig.holder(bone).withAttachment(spec))
                    }
                    state.select(bone, added?.takeIf(::hasGizmo)?.id)
                }
            },
        ).takeIf { kinds.isNotEmpty() },
        node?.let {
            UiDropdownItem(
                rigText(if (it.isVisible) "hide_in_preview" else "show_in_preview"),
                icon = if (it.isVisible) InvisibleIcon else VisibleIcon,
                separatorBefore = kinds.isNotEmpty(),
            ) { state.viewer.toggleNodeVisibility(it) }
        },
    )
}

private fun List<RuntimeNode>.findBone(name: String): RuntimeNode? =
    firstNotNullOfOrNull { root -> root.walk().firstOrNull { it.name == name } }
