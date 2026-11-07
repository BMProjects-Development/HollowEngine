package ru.hollowhorizon.hollowengine.addons.narrative.graph

import ru.hollowhorizon.hollowengine.addons.narrative.components.ComponentCommands
import ru.hollowhorizon.hollowengine.addons.narrative.scene.NarrativeActions
import ru.hollowhorizon.hollowengine.addons.narrative.story.TypeOperators
import ru.hollowhorizon.hollowengine.addons.narrative.story.ValueOperators
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.npcs.navigation.JumpSettings
import ru.hollowhorizon.hollowengine.common.npcs.navigation.NavigationComponent
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Fields that are data classes themselves, which blocks fold into a section under the pin of the whole value. */
class StoryPinSectionsTest {
    private val id = "test:navigation".rl
    private val navigation = NavigationComponent::class.qualifiedName!!
    private val jumps = JumpSettings::class.qualifiedName!!

    @BeforeTest
    fun register() {
        NarrativeActions.register()
        if (ComponentDescriptorRegistry.descriptorOrNull(id) == null) {
            ComponentDescriptorRegistry.register(
                ComponentDescriptor(id, NavigationComponent::class, NavigationComponent.serializer())
            )
        }
    }

    @AfterTest
    fun cleanup() {
        ComponentDescriptorRegistry.unregisterDescriptor(id)
    }

    private fun read(value: StoryNode.Pure, pin: String, vararg extra: StoryNode, links: List<StoryLink> = emptyList()) =
        StoryGraphCompiler.compile(
            StoryGraph(
                nodes = listOf(StoryNode.Start("s"), StoryNode.Say("say"), value) + extra,
                links = listOf(
                    StoryLink("s", StoryPins.NEXT, "say", StoryPins.IN),
                    StoryLink(value.id, pin, "say", StoryPins.TEXT),
                ) + links,
            ),
            StoryFunctions.Builtin,
        )

    @Test
    fun `a section of a component is a parameter of its own, its fields after it`() {
        val names = assertNotNull(ComponentCommands.signature("component/$id")).params.map { it.name }
        val section = names.indexOf("jumps")
        assertTrue(section >= 0, "the settings of jumps are given at once")
        assertEquals("jumps.maxGap", names.drop(section + 1).first { it.startsWith("jumps.") && it.endsWith("maxGap") })
        assertTrue(names.drop(section + 1).takeWhile { it.startsWith("jumps.") }.isNotEmpty())
    }

    @Test
    fun `a value is taken apart into its sections and their fields`() {
        val unpack = assertNotNull(StoryOperators.find(TypeOperators.UNPACK + navigation))
        val paths = unpack.outputs.map { it.path }
        assertTrue("jumps" in paths && "jumps.maxGap" in paths)
        val story = read(StoryNode.Pure("parts", TypeOperators.UNPACK + navigation, mapOf("value" to "nav")), "jumps.maxGap")
        assertContains(story.code, "(nav).jumps.maxGap")
    }

    @Test
    fun `a section is put together from the fields given, or taken whole when linked`() {
        val pack = TypeOperators.PACK + navigation
        val operator = assertNotNull(StoryOperators.find(pack))
        assertTrue(operator.inputs.map { it.path }.containsAll(listOf("jumps", "jumps.maxGap")))

        val parts = read(StoryNode.Pure("made", pack, mapOf("jumps.maxGap" to "3")), ValueOperators.VALUE)
        assertEquals(emptyList(), parts.problems)
        assertContains(parts.code, "NavigationComponent(jumps = JumpSettings(maxGap = 3))")
        assertContains(parts.code, "import $jumps")

        val empty = read(StoryNode.Pure("made", pack), ValueOperators.VALUE)
        assertContains(empty.code, "NavigationComponent()")

        val whole = read(
            StoryNode.Pure("made", pack, mapOf("jumps.maxGap" to "3")), ValueOperators.VALUE,
            StoryNode.Pure("jumps", TypeOperators.PACK + jumps, mapOf("maxGap" to "4")),
            links = listOf(StoryLink("jumps", ValueOperators.VALUE, "made", "jumps")),
        )
        assertContains(whole.code, "NavigationComponent(jumps = JumpSettings(maxGap = 4))")
    }
}
