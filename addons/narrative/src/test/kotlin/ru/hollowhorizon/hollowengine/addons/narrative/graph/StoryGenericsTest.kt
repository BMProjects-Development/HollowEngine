package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class StoryGenericsTest {
    @Test
    fun `the element of a list comes from the type it goes into`() {
        assertEquals("a.BlockCost", StoryGenerics.elementOf("List<T>", "kotlin.collections.List<a.BlockCost>"))
        assertEquals("Pair<A, B>", StoryGenerics.elementOf("List<T>", "Iterable<out Pair<A, B>>?"))
        assertEquals("Int", StoryGenerics.elementOf("T", "Int"))
        assertNull(StoryGenerics.elementOf("List<T>", "Map<String, Int>"))
        assertNull(StoryGenerics.elementOf("List<T>", "List<*>"))
        assertNull(StoryGenerics.elementOf("List<T>", "Int"))
    }

    @Test
    fun `a type that says nothing of a value is not taken for T`() {
        assertFalse(StoryGenerics.isKnown("kotlin.Any"))
        assertFalse(StoryGenerics.isKnown("List<Nothing>"))
        assertFalse(StoryGenerics.isKnown("List<T>"))
        assertEquals("List<Int>", StoryGenerics.shaped("List<T>", "Int"))
    }
}
