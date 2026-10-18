package ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CollectOptionsTest {
    @Test
    fun `collection rejects invalid search interval`() {
        assertFailsWith<IllegalArgumentException> { CollectOptions(searchIntervalTicks = 0) }
    }
}
