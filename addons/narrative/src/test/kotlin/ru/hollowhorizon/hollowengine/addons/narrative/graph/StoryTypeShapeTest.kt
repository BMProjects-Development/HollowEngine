package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.reflect.KVariance

class StoryTypeShapeTest {
    @Test
    fun `nested nullable shapes retain arbitrary parameter identities`() {
        val key = StoryTypeShape.parameter("Key")
        val element = StoryTypeShape.parameter("Element")
        val shape = StoryTypeShape.mapOf(key, StoryTypeShape.listOf(element.nullable)).nullable
        assertEquals("kotlin.collections.Map<Key, kotlin.collections.List<Element?>>?", shape.kotlin)
        assertEquals(listOf("Key", "Element"), shape.parameters)
        assertEquals("kotlin.collections.Map<*, kotlin.collections.List<*>>?", shape.starred)
        assertNull(element.nullable.starred)
        assertEquals(element, element.nullable.nonNullable)
    }

    @Test
    fun `repeated parameters occur only once and concrete arguments stay concrete`() {
        val element = StoryTypeShape.parameter("Element")
        val repeated = StoryTypeShape.mapOf(element, element)
        val concrete = StoryTypeShape.mapOf(StoryTypeShape.of(String::class), element)
        assertEquals(listOf("Element"), repeated.parameters)
        assertEquals("kotlin.collections.Map<kotlin.String, *>", concrete.starred)
        assertFailsWith<IllegalArgumentException> { StoryTypeShape.of(Map::class, element) }
    }

    @Test
    fun `projections are structural and erase without invalid star variance`() {
        val parameter = StoryTypeShape.parameter("Item")
        val shape = StoryTypeShape.projected(
            List::class, StoryTypeShape.Projection(parameter.nullable, KVariance.OUT),
        )
        assertEquals("kotlin.collections.List<out Item?>", shape.kotlin)
        assertEquals("kotlin.collections.List<*>", shape.starred)
        assertEquals("kotlin.collections.MutableList<Item>", StoryTypeShape.mutableListOf(parameter).kotlin)
        assertEquals("kotlin.collections.MutableMap<*, *>", StoryTypeShape.mutableMapOf(parameter, parameter).starred)
    }
}
