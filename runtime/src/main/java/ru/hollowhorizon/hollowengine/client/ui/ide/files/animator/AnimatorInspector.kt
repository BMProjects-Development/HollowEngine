package ru.hollowhorizon.hollowengine.client.ui.ide.files.animator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeAnimatorDocument
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContributor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiSyntaxHighlighter
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextDiagnostic
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextInputFilter
import ru.hollowhorizon.hollowengine.common.models.*


private const val AnimatorIcon = "hollowengine:textures/gui/icons/state.svg"

internal fun animatorInspectorTarget(
    document: HollowIdeAnimatorDocument,
    selection: AnimatorSelection,
    onSelect: (AnimatorSelection) -> Unit,
): InspectorTarget? = when (selection) {
    is AnimatorSelection.None -> null

    is AnimatorSelection.Layer -> InspectorTarget(
        id = "animator-layer-${selection.layerId}",
        title = selection.layerId,
        icon = AnimatorIcon,
        subtitle = animatorText("section_layer"),
    ) { LayerSection(document, selection.layerId) { renamed -> onSelect(AnimatorSelection.Layer(renamed)) } }

    is AnimatorSelection.State -> InspectorTarget(
        id = "animator-state-${selection.layerId}-${selection.stateId}",
        title = selection.stateId,
        icon = AnimatorIcon,
        subtitle = animatorText("section_state"),
    ) { StateSection(document, selection, onSelect) }

    is AnimatorSelection.Transition -> InspectorTarget(
        id = "animator-link-${selection.layerId}-${selection.index}",
        title = document.animator.controller(selection.layerId)
            ?.transitions?.getOrNull(selection.index)
            ?.let { "${it.from} → ${it.to}" }
            ?: animatorText("section_link"),
        icon = AnimatorIcon,
        subtitle = animatorText("section_transition"),
    ) { TransitionSection(document, selection, onSelect) }
}

@Composable
private fun LayerSection(
    document: HollowIdeAnimatorDocument,
    layerId: String,
    onRenamed: (String) -> Unit,
) {
    val layer = document.animator.layer(layerId) ?: return Hint(animatorText("layer_removed"))

    Section(animatorText("section_layer")) {
        Readonly(animatorText("kind"), layer.kindName())
        NameRow(animatorText("name"), layer.id) { value ->
            document.edit { it.withLayerRenamed(layerId, value) }
            onRenamed(value)
        }
        IntRow(animatorText("priority"), layer.priority) { value -> document.edit { it.withLayer(layer.withCommon(priority = value)) } }
        ExpressionField(animatorText("weight"), layer.weight.source) { value ->
            document.edit { it.withLayer(layer.withCommon(weight = AnimationExpression(value))) }
        }
        FloatRow(animatorText("fade_in"), layer.fadeIn) { value -> document.edit { it.withLayer(layer.withCommon(fadeIn = value)) } }
        FloatRow(animatorText("fade_out"), layer.fadeOut) { value -> document.edit { it.withLayer(layer.withCommon(fadeOut = value)) } }
        Label(animatorText("blend"))
        Pills(LayerBlendMode.entries, layer.blendMode, { it.name.lowercase() }) { mode ->
            document.edit { it.withLayer(layer.withCommon(blendMode = mode)) }
        }
    }

    when (layer) {
        is AnimationControllerLayerSpec -> Section(animatorText("section_controller")) {
            Readonly(animatorText("states"), layer.states.size.toString())
            Readonly(animatorText("transitions"), layer.transitions.size.toString())
            Readonly(animatorText("entry"), layer.entryState ?: animatorText("none"))
        }

        is ClipAnimationLayerSpec -> Section(animatorText("section_clip")) {
            TextRow(animatorText("animation"), layer.animation) { value ->
                document.edit { it.withLayer(layer.copy(animation = value)) }
            }
            PlayModeRow(layer.playMode) { mode -> document.edit { it.withLayer(layer.copy(playMode = mode)) } }
            ExpressionField(animatorText("speed"), layer.speed.source) { value ->
                document.edit { it.withLayer(layer.copy(speed = AnimationExpression(value))) }
            }
        }

        is ProceduralLayerSpec -> Section(animatorText("section_procedural")) {
            layer.transforms.forEach { transform -> Readonly(animatorText("bone"), transform.bone) }
            if (layer.transforms.isEmpty()) Hint(animatorText("no_transforms"))
        }

        else -> Section(layer.kindName()) {
            if (layer is UnknownAnimatorLayerSpec) Hint(animatorText("unknown_layer"))
            else Hint(animatorText("no_layer_editor"))
        }
    }
}

@Composable
private fun StateSection(
    document: HollowIdeAnimatorDocument,
    selection: AnimatorSelection.State,
    onSelect: (AnimatorSelection) -> Unit,
) {
    val layerId = selection.layerId
    val controller = document.animator.controller(layerId) ?: return Hint(animatorText("layer_removed"))

    if (selection.stateId == ANY_STATE) {
        Section(animatorText("section_any_state")) {
            Hint(animatorText("any_state_hint"))
        }
        return
    }

    val state = controller.states.firstOrNull { it.id == selection.stateId } ?: return Hint(animatorText("state_removed"))

    Section(animatorText("section_state")) {
        Readonly(animatorText("kind"), state.kindName())
        NameRow(animatorText("name"), state.id) { value ->
            if (controller.states.none { it.id == value }) {
                document.edit { it.withStateRenamed(layerId, state.id, value) }
                onSelect(AnimatorSelection.State(layerId, value))
            }
        }

        when (state) {
            is ClipStateSpec -> {
                TextRow(animatorText("animation"), state.animation) { value ->
                    document.edit { it.withState(layerId, state.copy(animation = value)) }
                }
                PlayModeRow(state.playMode) { mode ->
                    document.edit { it.withState(layerId, state.copy(playMode = mode)) }
                }
                ExpressionField(animatorText("speed"), state.speed.source) { value ->
                    document.edit { it.withState(layerId, state.copy(speed = AnimationExpression(value))) }
                }
            }

            is BlendStateSpec -> BlendStateFields(document, layerId, state)

            // A state this build has no editor for: an addon's own kind, or one whose addon is missing.
            is UnknownAnimatorStateSpec -> Hint(animatorText("unknown_state"))

            else -> Hint(animatorText("no_state_editor"))
        }
    }

    if (state is BlendStateSpec) BlendMotionsSection(document, layerId, state)

    val links = controller.transitions.withIndex()
        .filter { (_, transition) -> transition.from == state.id || transition.to == state.id }
    if (links.isNotEmpty()) {
        Section(animatorText("state_transitions")) {
            links.forEach { (index, transition) ->
                InspectorButton("${transition.from} → ${transition.to}") {
                    onSelect(AnimatorSelection.Transition(layerId, index))
                }
            }
        }
    }

    InspectorButton(animatorText("make_entry"), tags = listOf("primary")) {
        document.edit { it.withEntryState(layerId, state.id) }
    }
    InspectorButton(animatorText("delete_state"), tags = listOf("danger")) {
        document.edit { it.withoutState(layerId, state.id) }
        onSelect(AnimatorSelection.Layer(layerId))
    }
}

@Composable
private fun TransitionSection(
    document: HollowIdeAnimatorDocument,
    selection: AnimatorSelection.Transition,
    onSelect: (AnimatorSelection) -> Unit,
) {
    val layerId = selection.layerId
    val controller = document.animator.controller(layerId) ?: return Hint(animatorText("layer_removed"))
    val transition = controller.transitions.getOrNull(selection.index) ?: return Hint(animatorText("transition_removed"))

    fun update(change: (AnimationControllerTransitionSpec) -> AnimationControllerTransitionSpec) {
        document.edit { it.withTransitionAt(layerId, selection.index, change(transition)) }
    }

    Section(animatorText("section_link")) {
        Readonly(animatorText("transition"), "${transition.from} → ${transition.to}")
    }

    Section(animatorText("section_transition")) {
        ExpressionField(animatorText("condition"), transition.condition.source) { value ->
            update { it.copy(condition = AnimationExpression(value)) }
        }
        ExpressionField(animatorText("duration"), transition.duration.source) { value ->
            update { it.copy(duration = AnimationExpression(value)) }
        }
        IntRow(animatorText("priority"), transition.priority) { value -> update { it.copy(priority = value) } }
        FloatRow(animatorText("exit_time"), transition.exitTime ?: 0f) { value ->
            update { it.copy(exitTime = value.takeIf { time -> time > 0f }) }
        }
    }

    InspectorButton(animatorText("delete_transition"), tags = listOf("danger")) {
        document.edit { it.withoutTransitionAt(layerId, selection.index) }
        onSelect(AnimatorSelection.Layer(layerId))
    }
}

@Composable
internal fun PlayModeRow(current: AnimationPlayMode, onChange: (AnimationPlayMode) -> Unit) {
    Label(animatorText("play_mode"))
    Pills(AnimationPlayMode.entries, current, { it.name.lowercase() }, onChange)
}

