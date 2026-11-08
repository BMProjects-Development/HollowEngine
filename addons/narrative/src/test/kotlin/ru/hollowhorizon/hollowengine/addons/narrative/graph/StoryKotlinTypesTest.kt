package ru.hollowhorizon.hollowengine.addons.narrative.graph

import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.MappedClass
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals

class StoryKotlinTypesTest {
    private fun mappings(named: String) = object : Mappings {
        override val namespaces = listOf("intermediary", "named")
        override val classes = listOf(MappedClass(
            listOf(Nested::class.java.name.replace('.', '/'), named), emptyList(), emptyList(), emptyList(),
        ))
    }

    @Test
    fun `known runtime classes use named mappings including nested class separators`() {
        assertEquals("game.Outer.Inner", StoryKotlinTypes.className(Nested::class, mappings("game/Outer\$Inner")))
        assertEquals(Nested::class.qualifiedName, StoryKotlinTypes.className(Nested::class, Mappings.EMPTY))
    }

    @Test
    fun `parallel lookups keep each mapping snapshot consistent`() {
        val first = mappings("first/Outer\$Inner")
        val second = mappings("second/Outer\$Inner")
        Executors.newFixedThreadPool(4).use { executor ->
            val results = executor.invokeAll(List(64) { index -> Callable {
                val mappings = if (index % 2 == 0) first else second
                StoryKotlinTypes.className(Nested::class, mappings)
            } }).map { it.get() }
            assertEquals(List(64) { if (it % 2 == 0) "first.Outer.Inner" else "second.Outer.Inner" }, results)
        }
    }

    @Test
    fun `use site variance and nested nullability survive serialization of known types`() {
        assertEquals(
            "kotlin.collections.Map<kotlin.String, kotlin.collections.List<out kotlin.Number?>>",
            StoryKotlinTypes.name(typeOf<Map<String, List<out Number?>>>()),
        )
    }

    class Nested
}
