package ru.hollowhorizon.hollowengine.common.scripting.ide

import org.junit.jupiter.api.Test
import kotlin.test.*

class CompletionMatchTest {
    @Test
    fun `camel hump input matches separated name parts`() {
        val match = assertNotNull(matchCompletion("HollEng", "HollowEngine"))

        assertEquals(listOf(0..3, 6..8), match.ranges)
    }

    @Test
    fun `characters must stay in source order`() {
        assertNull(matchCompletion("EngineHollow", "HollowEngine"))
    }

    @Test
    fun `a compact prefix ranks ahead of a longer prefix`() {
        val short = assertNotNull(matchCompletion("Minecr", "Minecraft"))
        val long = assertNotNull(matchCompletion("Minecr", "MinecraftHssResourceLoader"))

        assertTrue(short.score < long.score)
    }

    @Test
    fun `prefix ranks ahead of a subsequence`() {
        val prefix = assertNotNull(matchCompletion("Core", "CoreLoader"))
        val subsequence = assertNotNull(matchCompletion("Core", "HollowCore"))

        assertTrue(prefix.score < subsequence.score)
    }

    @Test
    fun `a new chunk may only start on a word boundary`() {
        assertNull(matchCompletion("iner", "Minecraft"), "'ine' + 'r' would restart mid-word")
        assertNull(matchCompletion("mncrf", "Minecraft"))
        assertNotNull(matchCompletion("Loader", "MinecraftResourceLoader"))
    }

    @Test
    fun `an acronym matches humps`() {
        val match = assertNotNull(matchCompletion("MRL", "MinecraftResourceLoader"))

        assertEquals(listOf(0..0, 9..9, 17..17), match.ranges)
    }

    @Test
    fun `matching backtracks out of a dead end`() {
        val match = assertNotNull(matchCompletion("Mc", "MinecraftClass"))

        assertEquals(listOf(0..0, 9..9), match.ranges)
    }

    @Test
    fun `the first typed letter must match case`() {
        assertNull(matchCompletion("min", "Minecraft"), "a lowercase search does not want classes")
        assertNull(matchCompletion("Min", "minValue"))
        assertNotNull(matchCompletion("Min", "Minecraft"))
        assertNotNull(matchCompletion("min", "minValue"))
        assertNotNull(matchCompletion("Mrl", "MinecraftResourceLoader"))
    }

    @Test
    fun `case sensitivity can be relaxed or tightened`() {
        assertNotNull(matchCompletion("min", "Minecraft", CompletionCaseSensitivity.NONE))
        assertNotNull(matchCompletion("MRL", "MinecraftResourceLoader", CompletionCaseSensitivity.ALL))
        assertNull(matchCompletion("Mrl", "MinecraftResourceLoader", CompletionCaseSensitivity.ALL))
    }

    @Test
    fun `a case-rejected prefix still matches a later word`() {
        val match = assertNotNull(matchCompletion("Min", "minMinecraft"))

        assertEquals(listOf(3..5), match.ranges)
    }

    @Test
    fun `characters without case are never rejected`() {
        assertNotNull(matchCompletion("@pv", "@play-video"))
        assertNotNull(matchCompletion("3d", "3dModel"))
    }

    @Test
    fun `underscores and dashes open a new word`() {
        assertNotNull(matchCompletion("pv", "@play-video"))
        assertNotNull(matchCompletion("mb", "my_box"))
        assertNull(matchCompletion("yb", "my_box"))
    }

    @Test
    fun `an exact match beats a prefix and case-sensitive beats case-insensitive`() {
        val none = CompletionCaseSensitivity.NONE
        val exact = assertNotNull(matchCompletion("open", "open", none))
        val exactOtherCase = assertNotNull(matchCompletion("open", "Open", none))
        val prefix = assertNotNull(matchCompletion("open", "openFile", none))
        val prefixOtherCase = assertNotNull(matchCompletion("open", "OpenFile", none))

        assertTrue(exact.score < exactOtherCase.score)
        assertTrue(exactOtherCase.score < prefix.score)
        assertTrue(prefix.score < prefixOtherCase.score)
    }

    @Test
    fun `an empty pattern does not prefer short names`() {
        val short = assertNotNull(matchCompletion("", "id"))
        val long = assertNotNull(matchCompletion("", "MinecraftHssResourceLoader"))

        assertEquals(short.score, long.score, "ordering is left to the item's closeness")
    }

    @Test
    fun `matching agrees with the filter`() {
        assertTrue(completionMatches("HollEng", "HollowEngine"))
        assertTrue(completionMatches(null, "HollowEngine"))
        assertFalse(completionMatches("iner", "Minecraft"))
        assertFalse(completionMatches("HollEngX", "HollowEngine"))
        assertFalse(completionMatches("holl", "HollowEngine"))
        assertTrue(completionMatches("holl", "HollowEngine", CompletionCaseSensitivity.NONE))
    }
}
