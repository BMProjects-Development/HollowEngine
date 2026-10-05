package ru.hollowhorizon.hollowengine.client.ui.ide.files.animator

import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.ide.SceneTarget
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeAnimatorDocument
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiKeyInput
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeItem
import ru.hollowhorizon.hollowengine.common.models.ANY_STATE
import ru.hollowhorizon.hollowengine.common.models.AnimatorLayerTypes
import ru.hollowhorizon.hollowengine.common.models.GraphPoint
import ru.hollowhorizon.hollowengine.common.models.controller
import ru.hollowhorizon.hollowengine.common.models.nodeAt
import ru.hollowhorizon.hollowengine.common.models.withLayer
import ru.hollowhorizon.hollowengine.common.models.withoutAnyState
import ru.hollowhorizon.hollowengine.common.models.withoutLayer
import ru.hollowhorizon.hollowengine.common.models.withoutState

private const val LayerIcon = "hollowengine:textures/gui/icons/layers.svg"
private const val StateIcon = "hollowengine:textures/gui/icons/state.svg"
private const val AnyStateIcon = "hollowengine:textures/gui/icons/any_state.svg"

/** Tree row ids: a layer, and a state under it, which is only unique together with its layer. */
private const val LayerRow = "layer:"
private const val StateRow = "state:"

private fun layerRow(layerId: String) = LayerRow + layerId
private fun stateRow(layerId: String, stateId: String) = "$StateRow$layerId/$stateId"

/** The row a tree id stands for, as the selection it makes. */
private fun selectionOf(row: String): AnimatorSelection? = when {
    row.startsWith(LayerRow) -> AnimatorSelection.Layer(row.removePrefix(LayerRow))
    row.startsWith(StateRow) -> row.removePrefix(StateRow).split('/', limit = 2)
        .takeIf { it.size == 2 }?.let { (layer, state) -> AnimatorSelection.State(layer, state) }

    else -> null
}

internal fun animatorSceneTarget(
    document: HollowIdeAnimatorDocument,
    selection: AnimatorSelection,
    expanded: Collection<String>,
    onToggleLayer: (String) -> Unit,
    onSelect: (AnimatorSelection) -> Unit,
) = SceneTarget(
    id = "animator-layers",
    history = document.history,
    items = buildList {
        document.animator.layers.forEach { layer ->
            val controller = document.animator.controller(layer.id)
            val states = controller?.states?.map { it.id }.orEmpty() +
                    listOfNotNull(ANY_STATE.takeIf { document.animator.nodeAt(layer.id, ANY_STATE) != null })
            add(
                UiTreeItem<Any?>(
                    id = layerRow(layer.id),
                    label = "${layer.id} · ${layer.kindName()}",
                    depth = 0,
                    payload = layer,
                    icon = LayerIcon,
                    hasChildren = states.isNotEmpty(),
                    expanded = layer.id in expanded,
                    selected = selection == AnimatorSelection.Layer(layer.id),
                )
            )
            if (layer.id !in expanded) return@forEach
            states.forEach { stateId ->
                add(
                    UiTreeItem(
                        id = stateRow(layer.id, stateId),
                        label = if (stateId == ANY_STATE) animatorText("any_state_node") else stateId,
                        depth = 1,
                        payload = null,
                        icon = if (stateId == ANY_STATE) AnyStateIcon else StateIcon,
                        selected = selection == AnimatorSelection.State(layer.id, stateId),
                    )
                )
            }
        }
    },
    onSelect = { row -> onSelect(row?.let(::selectionOf) ?: AnimatorSelection.None) },
    onToggle = { row -> if (row.startsWith(LayerRow)) onToggleLayer(row.removePrefix(LayerRow)) },
    hint = animatorText("no_layers").takeIf { document.animator.layers.isEmpty() },
    menu = { row -> sceneMenu(document, row?.let(::selectionOf), onSelect) },
    onKey = { input -> handleSceneKey(document, selection, onSelect, input) },
)

/** What a right click offers: adding a layer anywhere, and what can be done to the row under it. */
private fun sceneMenu(
    document: HollowIdeAnimatorDocument,
    target: AnimatorSelection?,
    onSelect: (AnimatorSelection) -> Unit,
): List<UiDropdownItem> {
    val addLayer = UiDropdownItem(
        animatorText("add_layer"),
        children = AnimatorLayerTypes.all.mapNotNull { type ->
            val createDefault = type.createDefault ?: return@mapNotNull null
            UiDropdownItem(type.title()) {
                val id = freeLayerId(document.animator, type.id.substringAfterLast('/'))
                document.edit { it.withLayer(createDefault().withCommon(id = id)) }
                onSelect(AnimatorSelection.Layer(id))
            }
        },
    )
    return when (target) {
        is AnimatorSelection.Layer -> buildList {
            add(addLayer)
            document.animator.controller(target.layerId)?.let { controller ->
                addAll(addStateItems(document, target.layerId, controller, GraphPoint(), onSelect))
            }
            add(UiDropdownItem(animatorText("delete_layer"), separatorBefore = true) {
                document.edit { it.withoutLayer(target.layerId) }
                onSelect(AnimatorSelection.None)
            })
        }

        is AnimatorSelection.State -> stateMenu(document, target.layerId, target.stateId, onSelect)
        else -> listOf(addLayer)
    }
}

private fun handleSceneKey(
    document: HollowIdeAnimatorDocument,
    selection: AnimatorSelection,
    onSelect: (AnimatorSelection) -> Unit,
    input: UiKeyInput,
): Boolean {
    if (input.key != GLFW.GLFW_KEY_DELETE) return false
    when (selection) {
        is AnimatorSelection.Layer -> {
            document.edit { it.withoutLayer(selection.layerId) }
            onSelect(AnimatorSelection.None)
        }

        is AnimatorSelection.State -> {
            document.edit {
                if (selection.stateId == ANY_STATE) it.withoutAnyState(selection.layerId)
                else it.withoutState(selection.layerId, selection.stateId)
            }
            onSelect(AnimatorSelection.Layer(selection.layerId))
        }

        else -> return false
    }
    return true
}
