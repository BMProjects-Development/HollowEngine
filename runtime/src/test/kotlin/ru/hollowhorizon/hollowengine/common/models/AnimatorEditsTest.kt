package ru.hollowhorizon.hollowengine.common.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnimatorEditsTest {
    private val animator = Animator(
        layers = listOf(
            AnimationControllerLayerSpec(
                id = "layer",
                states = listOf("a", "b", "c").map { ClipStateSpec(id = it, animation = it) },
                transitions = listOf(
                    AnimationControllerTransitionSpec(from = "a", to = "b"),
                    AnimationControllerTransitionSpec(from = "b", to = "c"),
                ),
            )
        )
    )

    @Test
    fun `moving one state keeps the default places of the others`() {
        val before = animator.nodeLayout("layer")

        val moved = animator.withNodeAt("layer", "b", GraphPoint(700f, 500f)).nodeLayout("layer")

        assertEquals(before - "b", moved - "b")
    }

    @Test
    fun `removing a state keeps the default places of the others`() {
        val before = animator.nodeLayout("layer")

        val left = animator.withoutState("layer", "a").nodeLayout("layer")

        assertEquals(before - "a", left)
    }

    @Test
    fun `a transition is found and removed by its states, wherever it is in the list`() {
        val shifted = animator.withoutTransition("layer", "a", "b")

        val controller = checkNotNull(shifted.controller("layer"))
        assertNull(controller.transition("a", "b"))
        assertEquals(listOf("b" to "c"), controller.transitions.map { it.from to it.to })
    }
}
