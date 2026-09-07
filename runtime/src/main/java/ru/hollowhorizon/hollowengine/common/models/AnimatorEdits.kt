package ru.hollowhorizon.hollowengine.common.models

/** The layer with this id, or null. */
fun Animator.layer(layerId: String): AnimatorLayerSpec? = layers.firstOrNull { it.id == layerId }

fun Animator.controller(layerId: String): AnimationControllerLayerSpec? =
    layer(layerId) as? AnimationControllerLayerSpec

fun Animator.withLayer(layer: AnimatorLayerSpec): Animator {
    val index = layers.indexOfFirst { it.id == layer.id }
    if (index < 0) return copy(layers = layers + layer)
    return copy(layers = layers.toMutableList().also { it[index] = layer })
}

/**
 * Renames a layer, carrying the canvas positions of its states with it.
 */
fun Animator.withLayerRenamed(layerId: String, name: String): Animator {
    val layer = layer(layerId) ?: return this
    val target = name.trim()
    if (target.isEmpty() || target == layerId) return this
    if (layers.any { it.id == target }) return this

    val renamed = layer.withCommon(id = target)
    val prefix = "$layerId/"
    return copy(
        layers = layers.map { if (it.id == layerId) renamed else it },
        layout = layout.mapKeys { (key, _) ->
            if (key.startsWith(prefix)) "$target/${key.removePrefix(prefix)}" else key
        },
    )
}

/** Drops a layer, and the canvas positions that only made sense inside it. */
fun Animator.withoutLayer(layerId: String): Animator {
    if (layers.none { it.id == layerId }) return this
    val prefix = "$layerId/"
    return copy(
        layers = layers.filterNot { it.id == layerId },
        layout = layout.filterKeys { !it.startsWith(prefix) },
    )
}

/**
 * Adds a state to a controller layer, or replaces the one with the same id.
 */
fun Animator.withState(
    layerId: String,
    state: AnimationControllerStateSpec,
    at: GraphPoint? = null,
): Animator {
    val controller = controller(layerId) ?: return this
    val states = controller.states.map { if (it.id == state.id) state else it }
        .let { if (it.any { existing -> existing.id == state.id }) it else it + state }
    val updated = withLayer(
        controller.copy(
            states = states,
            entryState = controller.entryState ?: state.id,
        )
    )
    val place = at ?: nodeAt(layerId, state.id) ?: return updated
    return updated.withNodeAt(layerId, state.id, place)
}

/** Renames a state, carrying its transitions and its place on the canvas with it. */
fun Animator.withStateRenamed(layerId: String, stateId: String, name: String): Animator {
    val controller = controller(layerId) ?: return this
    val target = name.trim()
    if (target.isEmpty() || target == stateId) return this
    if (controller.states.none { it.id == stateId }) return this
    if (controller.states.any { it.id == target }) return this

    val point = nodeAt(layerId, stateId)
    return withLayer(
        controller.copy(
            states = controller.states.map { if (it.id == stateId) it.withId(target) else it },
            transitions = controller.transitions.map { transition ->
                transition.copy(
                    from = if (transition.from == stateId) target else transition.from,
                    to = if (transition.to == stateId) target else transition.to,
                )
            },
            entryState = if (controller.entryState == stateId) target else controller.entryState,
        )
    ).copy(
        layout = (layout - graphKey(layerId, stateId)).let { rest ->
            if (point == null) rest else rest + (graphKey(layerId, target) to point)
        }
    )
}

/**
 * Removes a state, along with everything that pointed at it.
 */
fun Animator.withoutState(layerId: String, stateId: String): Animator {
    val controller = controller(layerId) ?: return this
    if (controller.states.none { it.id == stateId }) return this

    val states = controller.states.filterNot { it.id == stateId }
    return withLayer(
        controller.copy(
            states = states,
            transitions = controller.transitions.filterNot { it.from == stateId || it.to == stateId },
            entryState = controller.entryState?.takeIf { it != stateId } ?: states.firstOrNull()?.id,
        )
    ).copy(layout = layout - graphKey(layerId, stateId))
}

/**
 * Turns a state into the layer's "from anywhere" node.
 */
fun Animator.withStateAsAnyState(layerId: String, stateId: String): Animator {
    val controller = controller(layerId) ?: return this
    if (stateId == ANY_STATE || controller.states.none { it.id == stateId }) return this

    val states = controller.states.filterNot { it.id == stateId }
    val transitions = controller.transitions
        .filterNot { it.to == stateId }
        .map { if (it.from == stateId) it.copy(from = ANY_STATE) else it }
        .distinctBy { it.from to it.to }
    val point = nodeAt(layerId, stateId)

    return withLayer(
        controller.copy(
            states = states,
            transitions = transitions,
            entryState = controller.entryState?.takeIf { it != stateId } ?: states.firstOrNull()?.id,
        )
    ).copy(
        layout = (layout - graphKey(layerId, stateId)).let { rest ->
            if (point == null) rest else rest + (graphKey(layerId, ANY_STATE) to point)
        }
    )
}

/** Puts the "from anywhere" node on the canvas, before anything leads out of it. */
fun Animator.withAnyStateAt(layerId: String, at: GraphPoint): Animator {
    if (controller(layerId) == null) return this
    return withNodeAt(layerId, ANY_STATE, at)
}

/** Takes the "from anywhere" node away, and the transitions that only existed out of it. */
fun Animator.withoutAnyState(layerId: String): Animator {
    val controller = controller(layerId) ?: return this
    val transitions = controller.transitions.filterNot { it.from == ANY_STATE }
    val layoutKey = graphKey(layerId, ANY_STATE)
    if (transitions.size == controller.transitions.size && layoutKey !in layout) return this

    return withLayer(controller.copy(transitions = transitions)).copy(layout = layout - layoutKey)
}

fun Animator.withEntryState(layerId: String, stateId: String): Animator {
    val controller = controller(layerId) ?: return this
    if (controller.states.none { it.id == stateId }) return this
    return withLayer(controller.copy(entryState = stateId))
}

/** Adds a transition, unless that exact pair is already connected. */
fun Animator.withTransition(layerId: String, transition: AnimationControllerTransitionSpec): Animator {
    val controller = controller(layerId) ?: return this
    val known = controller.states.map { it.id }.toSet() + ANY_STATE
    if (transition.from !in known || transition.to !in known) return this
    if (controller.transitions.any { it.from == transition.from && it.to == transition.to }) return this
    return withLayer(controller.copy(transitions = controller.transitions + transition))
}

fun Animator.withTransitionAt(
    layerId: String,
    index: Int,
    transition: AnimationControllerTransitionSpec,
): Animator {
    val controller = controller(layerId) ?: return this
    if (index !in controller.transitions.indices) return this
    return withLayer(
        controller.copy(transitions = controller.transitions.toMutableList().also { it[index] = transition })
    )
}

fun Animator.withoutTransitionAt(layerId: String, index: Int): Animator {
    val controller = controller(layerId) ?: return this
    if (index !in controller.transitions.indices) return this
    return withLayer(
        controller.copy(transitions = controller.transitions.filterIndexed { at, _ -> at != index })
    )
}

fun Animator.withNodeAt(layerId: String, stateId: String, at: GraphPoint): Animator =
    copy(layout = layout + (graphKey(layerId, stateId) to at))

fun Animator.nodeAt(layerId: String, stateId: String): GraphPoint? = layout[graphKey(layerId, stateId)]

/**
 * Positions for every state of a layer, inventing them for states that have none.
 */
fun Animator.nodeLayout(layerId: String): Map<String, GraphPoint> {
    val controller = controller(layerId) ?: return emptyMap()
    var placed = layout.count { it.key.startsWith("$layerId/") }
    return controller.states.associate { state ->
        val known = nodeAt(layerId, state.id)
        state.id to (known ?: defaultNodePosition(placed++))
    }
}

private fun defaultNodePosition(index: Int) = GraphPoint(
    x = NODE_COLUMN_STEP * (index % NODE_COLUMNS),
    y = NODE_ROW_STEP * (index / NODE_COLUMNS),
)

private const val NODE_COLUMNS = 4
private const val NODE_COLUMN_STEP = 170f
private const val NODE_ROW_STEP = 110f
