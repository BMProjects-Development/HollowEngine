package ru.hollowhorizon.hollowengine.client.ui.screen

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The demo stylesheet is only compiled when the screen opens, so a typo in it would otherwise
 * surface as a broken tab in game. Compiling it here turns that into a build failure.
 */
class DemoStylesheetTest {
    @Test
    fun `the demo stylesheet compiles`() {
        assertTrue(DemoStyles.rules.isNotEmpty(), "the stylesheet produced no rules")
    }

}
