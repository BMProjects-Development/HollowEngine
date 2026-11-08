import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinClassInfo
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinType
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinTypeParameter
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinTypeSystem
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinVariance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Subtyping, common supertypes and shapes follow Kotlin over the classes analysis describes. */
class KotlinTypeSystemTest {
    private val system = KotlinTypeSystem(
        listOf(
            info("kotlin.Any"),
            info("kotlin.Comparable", listOf(KotlinTypeParameter("T", KotlinVariance.IN)), interfaceOf = true),
            info("kotlin.CharSequence", interfaceOf = true),
            info("kotlin.String", supertypes = listOf("kotlin.Comparable<kotlin.String>", "kotlin.CharSequence")),
            info("kotlin.Number"),
            info("kotlin.Int", supertypes = listOf("kotlin.Number", "kotlin.Comparable<kotlin.Int>")),
            info("kotlin.collections.Iterable", listOf(out("T")), interfaceOf = true),
            info("kotlin.collections.Collection", listOf(out("E")), listOf("kotlin.collections.Iterable<E>"), true),
            info("kotlin.collections.List", listOf(out("E")), listOf("kotlin.collections.Collection<E>"), true),
            info("kotlin.collections.MutableList", listOf(KotlinTypeParameter("E")), listOf("kotlin.collections.List<E>"), true),
            info("java.util.ArrayList", listOf(KotlinTypeParameter("E")), listOf("kotlin.collections.MutableList<E>")),
            info("kotlin.collections.Map", listOf(KotlinTypeParameter("K"), out("V")), interfaceOf = true),
            info("game.Entity"),
            info("game.Living", supertypes = listOf("game.Entity")),
            info("game.Named", interfaceOf = true),
            info("game.Player", supertypes = listOf("game.Living", "game.Named")),
            info("game.Npc", supertypes = listOf("game.Living", "game.Named")),
            info("game.Party", supertypes = listOf("kotlin.collections.List<game.Player>")),
        ).associateBy { it.name },
    )

    @Test
    fun `a value fits a place of a class it is`() {
        assertEquals(true, fits("game.Player", "game.Entity"))
        assertEquals(false, fits("game.Entity", "game.Player"))
        assertEquals(true, fits("kotlin.String", "kotlin.Any"))
        assertEquals(false, fits("game.Player?", "game.Player"))
        assertEquals(true, fits("kotlin.Nothing", "game.Player"))
    }

    @Test
    fun `arguments follow the variance of their parameters`() {
        assertEquals(true, fits("kotlin.collections.List<game.Player>", "kotlin.collections.List<game.Entity>"))
        assertEquals(false, fits("kotlin.collections.MutableList<game.Player>", "kotlin.collections.MutableList<game.Entity>"))
        assertEquals(true, fits("kotlin.collections.MutableList<game.Player>", "kotlin.collections.MutableList<out game.Entity>"))
        assertEquals(true, fits("kotlin.Comparable<game.Entity>", "kotlin.Comparable<game.Player>"))
        assertEquals(true, fits("kotlin.collections.MutableList<game.Player>", "kotlin.collections.MutableList<*>"))
        assertEquals(false, fits("kotlin.collections.List<*>", "kotlin.collections.List<game.Player>"))
        assertEquals(true, fits("kotlin.collections.List<*>", "kotlin.collections.List<kotlin.Any?>"))
    }

    @Test
    fun `supertypes carry the arguments of their subtypes`() {
        assertEquals(true, fits("java.util.ArrayList<game.Npc>", "kotlin.collections.Iterable<game.Entity>"))
        assertEquals(true, fits("game.Party", "kotlin.collections.Collection<game.Living>"))
        assertEquals(false, fits("game.Party", "kotlin.collections.Collection<game.Npc>"))
        assertEquals<KotlinType?>(
            type("kotlin.collections.Iterable<out game.Npc>"),
            system.supertype(type("kotlin.collections.MutableList<out game.Npc>") as KotlinType.Class, "kotlin.collections.Iterable"),
        )
    }

    @Test
    fun `what turns on a class not described is not told`() {
        assertNull(fits("game.Unknown", "game.Entity"))
        assertNull(fits("kotlin.collections.List<game.Unknown>", "kotlin.collections.List<game.Entity>"))
        assertEquals(false, fits("game.Player", "game.Unknown"), "every class a player is is known, and none is that one")
    }

    @Test
    fun `the common supertype is the nearest class both are`() {
        assertEquals(type("game.Living"), common("game.Player", "game.Npc"))
        assertEquals(type("game.Living?"), common("game.Player?", "game.Npc", "kotlin.Nothing"))
        assertEquals(type("kotlin.collections.List<game.Living>"), common("kotlin.collections.List<game.Player>", "game.Party", "kotlin.collections.List<game.Npc>"))
        assertEquals(type("kotlin.Comparable<*>"), common("kotlin.String", "kotlin.Int"))
        assertEquals(type("kotlin.Any"), common("game.Entity", "kotlin.Number"))
        assertNull(common("game.Player", "game.Unknown"))
    }

    @Test
    fun `a shape takes its parameters from any supertype of the value`() {
        val list = KotlinType.parse("kotlin.collections.Iterable<T>", setOf("T"))!!
        assertEquals(mapOf("T" to type("game.Player")), system.match(list, type("game.Party")))
        assertEquals(mapOf("T" to type("game.Npc?")), system.match(list, type("java.util.ArrayList<game.Npc?>")))
        assertNull(system.match(list, type("kotlin.collections.Map<kotlin.String, game.Npc>")))
        val map = KotlinType.parse("kotlin.collections.Map<K, V>", setOf("K", "V"))!!
        assertEquals(
            mapOf("K" to type("kotlin.String"), "V" to type("game.Npc")),
            system.match(map, type("kotlin.collections.Map<kotlin.String, out game.Npc>")),
        )
        assertEquals(mapOf("T" to type("game.Npc")), system.match(KotlinType.parse("T?", setOf("T"))!!, type("game.Npc?")))
    }

    @Test
    fun `canonical text reads back as it renders`() {
        listOf("kotlin.collections.Map<kotlin.String, out a.B.C?>?", "kotlin.collections.List<*>", "kotlin.Comparable<in x.Y>")
            .forEach { assertEquals(it, type(it).render()) }
        assertNull(KotlinType.parse("(kotlin.Int) -> kotlin.String"))
        assertNull(KotlinType.parse("List<Int"))
    }

    private fun fits(from: String, into: String) = system.isSubtype(type(from), type(into))

    private fun common(vararg types: String) = system.commonSupertype(types.map(::type))

    private fun type(text: String) = KotlinType.parse(text) ?: error("Not a type: $text")

    private fun out(name: String) = KotlinTypeParameter(name, KotlinVariance.OUT)

    private fun info(
        name: String,
        parameters: List<KotlinTypeParameter> = emptyList(),
        supertypes: List<String> = listOf("kotlin.Any"),
        interfaceOf: Boolean = false,
    ) = KotlinClassInfo(
        name,
        parameters,
        if (name == "kotlin.Any") emptyList() else supertypes.map {
            KotlinType.parse(it, parameters.mapTo(HashSet()) { parameter -> parameter.name }) as KotlinType.Class
        },
        interfaceOf,
    )
}
