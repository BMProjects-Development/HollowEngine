package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonTargets
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bootstrap decides from these raw strings which addons get their libraries loaded before the game
 * starts, without going through [HollowAddonPlatform], so they are checked on their own.
 */
class HollowAddonTargetsTests {
    @Test
    fun `a common or missing platform runs on every loader`() {
        RuntimePlatform.entries.forEach { loader ->
            assertTrue(AddonTargets.runsOn(null, loader))
            assertTrue(AddonTargets.runsOn("", loader))
            assertTrue(AddonTargets.runsOn(" Common ", loader))
            assertFalse(AddonTargets.namesLoader("common", loader))
        }
    }

    @Test
    fun `a platform runs exactly on the loaders it names`() {
        assertTrue(AddonTargets.runsOn("fabric", RuntimePlatform.FABRIC))
        assertFalse(AddonTargets.runsOn("fabric", RuntimePlatform.NEOFORGE))
        assertTrue(AddonTargets.runsOn("neoforge,fabric", RuntimePlatform.FABRIC))
        assertTrue(AddonTargets.runsOn("neoforge,fabric", RuntimePlatform.NEOFORGE))
        assertTrue(AddonTargets.namesLoader("NeoForge", RuntimePlatform.NEOFORGE))
        assertFalse(AddonTargets.namesLoader("NeoForge", RuntimePlatform.FABRIC))
    }

    @Test
    fun `a platform naming anything else runs nowhere`() {
        listOf("forge", "fabric,forge", "common,fabric", ",").forEach { declared ->
            assertNull(AddonTargets.loadersOf(declared), declared)
            RuntimePlatform.entries.forEach { loader ->
                assertFalse(AddonTargets.runsOn(declared, loader), "$declared on $loader")
                assertFalse(AddonTargets.namesLoader(declared, loader), "$declared on $loader")
            }
        }
    }

    @Test
    fun `the canonical platform lists loaders once and in their own order`() {
        assertEquals("fabric,neoforge", AddonTargets.normalizePlatform(" NEOFORGE, fabric ,neoforge"))
        assertEquals("common", AddonTargets.normalizePlatform(null))
        assertEquals("fabric,forge", AddonTargets.normalizePlatform("Fabric,Forge"))
    }

    @Test
    fun `an environment keeps an addon to its side`() {
        assertTrue(AddonTargets.runsIn(null, true))
        assertTrue(AddonTargets.runsIn("common", false))
        assertTrue(AddonTargets.runsIn("Client", true))
        assertFalse(AddonTargets.runsIn("client", false))
        assertTrue(AddonTargets.runsIn("server", false))
        assertFalse(AddonTargets.runsIn("server", true))
        assertFalse(AddonTargets.runsIn("both", true))
        assertFalse(AddonTargets.runsIn("both", false))
    }
}
