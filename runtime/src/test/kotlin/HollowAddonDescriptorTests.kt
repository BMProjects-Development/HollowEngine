package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonVersions
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HollowAddonDescriptorTests {
    @Test
    fun `an addon without a platform is common and runs on both loaders`() {
        val descriptor = read("id=plain\nentry=com.example.Plain", RuntimePlatform.FABRIC)

        assertEquals(HollowAddonPlatform.COMMON, descriptor.platform)
        assertTrue(RuntimePlatform.entries.all(descriptor.platform::supports))
        assertTrue(RuntimePlatform.entries.none(descriptor.platform::names))
        assertTrue(!descriptor.platform.usesLoaderApi)
        assertEquals("com.example.Plain", descriptor.entrypoint)
    }

    @Test
    fun `keys of a loader replace the entrypoint and add to the lists only there`() {
        val text = """
            id=bridge
            platform=NeoForge, fabric
            entry=com.example.Common
            entry.neoforge=com.example.NeoForge
            dependsOn=library
            dependsOn.fabric=fabric-extras
            dependsOnMods=jei
            dependsOnMods.fabric=fabric-api
            requiredClasses.neoforge=net.neoforged.bus.api.IEventBus
        """.trimIndent()

        val onNeoForge = read(text, RuntimePlatform.NEOFORGE)
        assertEquals(HollowAddonPlatform.of(RuntimePlatform.FABRIC, RuntimePlatform.NEOFORGE), onNeoForge.platform)
        assertTrue(RuntimePlatform.entries.all(onNeoForge.platform::supports))
        assertEquals("com.example.NeoForge", onNeoForge.entrypoint)
        assertEquals(listOf("library"), onNeoForge.dependencies)
        assertEquals(listOf("jei"), onNeoForge.modDependencies)
        assertEquals(listOf("net.neoforged.bus.api.IEventBus"), onNeoForge.requiredClasses)

        val onFabric = read(text, RuntimePlatform.FABRIC)
        assertEquals("com.example.Common", onFabric.entrypoint)
        assertEquals(listOf("library", "fabric-extras"), onFabric.dependencies)
        assertEquals(listOf("jei", "fabric-api"), onFabric.modDependencies)
        assertTrue(onFabric.requiredClasses.isEmpty())
    }

    @Test
    fun `what a loader says about a dependency replaces what is said for every loader`() {
        val text = "id=bridge\nplatform=fabric,neoforge\ndependsOnMods=jei>=18, create\ndependsOnMods.neoforge=jei>=19?"

        assertEquals(
            listOf("jei>=19?", "create"),
            read(text, RuntimePlatform.NEOFORGE).modRequirements.map { it.toString() },
        )
        assertEquals(
            listOf("jei>=18", "create"),
            read(text, RuntimePlatform.FABRIC).modRequirements.map { it.toString() },
        )
    }

    @Test
    fun `a platform is a set of loaders however it is written`() {
        val both = HollowAddonPlatform.of(RuntimePlatform.NEOFORGE, RuntimePlatform.FABRIC)
        assertEquals("fabric,neoforge", both.id)
        assertEquals(both, HollowAddonPlatform.of(" NeoForge , fabric,neoforge "))
        assertEquals(HollowAddonPlatform.COMMON, HollowAddonPlatform.of(""))
        assertEquals(HollowAddonPlatform.COMMON, both.with(RuntimePlatform.FABRIC, false).with(RuntimePlatform.NEOFORGE, false))
        assertEquals(HollowAddonPlatform.of(RuntimePlatform.FABRIC), HollowAddonPlatform.COMMON.with(RuntimePlatform.FABRIC, true))
        assertEquals(null, HollowAddonPlatform.of("fabric,forge"))
        assertEquals(null, HollowAddonPlatform.of("common,fabric"))
        assertEquals(RuntimePlatform.entries, HollowAddonPlatform.COMMON.targets)
    }

    @Test
    fun `a single-loader addon supports only its loader`() {
        val descriptor = read("id=tweaks\nplatform=NeoForge\nenvironment=Client", RuntimePlatform.FABRIC)

        assertEquals(HollowAddonPlatform.of(RuntimePlatform.NEOFORGE), descriptor.platform)
        assertEquals(HollowAddonEnvironment.CLIENT, descriptor.environment)
        assertTrue(descriptor.platform.supports(RuntimePlatform.NEOFORGE))
        assertTrue(!descriptor.platform.supports(RuntimePlatform.FABRIC))
    }

    @Test
    fun `values and keys that would be ignored silently are refused`() {
        fun failure(text: String) =
            assertFailsWith<IllegalArgumentException> { read(text, RuntimePlatform.NEOFORGE) }.message.orEmpty()

        assertTrue("unknown platform 'forge'" in failure("id=a\nplatform=forge"))
        assertTrue("unknown environment 'both'" in failure("id=a\nenvironment=both"))
        assertTrue("unknown loader 'forge'" in failure("id=a\nentry.forge=com.example.A"))
        assertTrue("its platform is fabric" in failure("id=a\nplatform=fabric\ndependsOnMods.neoforge=jei"))
        assertTrue("unknown platform 'fabric,forge'" in failure("id=a\nplatform=fabric,forge"))
        assertTrue("a common addon knows no loader" in failure("id=a\nentry.neoforge=com.example.A"))
        assertTrue("a common addon knows no loader" in failure("id=a\ndependsOnMods.fabric=fabric-api"))
    }

    @Test
    fun `dependencies carry the versions they ask for and mods may be optional`() {
        val descriptor = read(
            "id=quests\ndependsOn=library>=1.2, core >= 1.0 < 2.0\ndependsOnMods=jei?, create>=0.5?, curios",
            RuntimePlatform.FABRIC,
        )

        assertEquals(listOf("library", "core"), descriptor.dependencies)
        assertEquals(listOf("library>=1.2", "core>=1.0<2.0"), descriptor.addonRequirements.map { it.toString() })
        assertEquals(listOf("jei", "create", "curios"), descriptor.modDependencies)
        assertEquals(listOf(true, true, false), descriptor.modRequirements.map { it.optional })
        assertEquals("create>=0.5?", descriptor.modRequirements[1].toString())
    }

    @Test
    fun `a version range accepts what every bound of it accepts`() {
        val range = HollowAddonDependency.parse("core>=1.2<2.0!=1.5.0")
        assertTrue(listOf("1.2", "1.2.0", "1.9.9", "1.5.1").all(range::accepts))
        assertTrue(listOf("1.1.9", "1.2.0-beta1", "2.0", "2.0.0", "1.5").none(range::accepts))
        assertTrue(HollowAddonDependency.parse("core").accepts("anything"))
    }

    @Test
    fun `every comparison holds exactly where its sign says, the bound itself included or not`() {
        fun accepted(spec: String) = listOf("1.0", "1.1", "1.2").filter(HollowAddonDependency.parse("core$spec")::accepts)

        assertEquals(listOf("1.1", "1.2"), accepted(">=1.1"))
        assertEquals(listOf("1.2"), accepted(">1.1"))
        assertEquals(listOf("1.0", "1.1"), accepted("<=1.1"))
        assertEquals(listOf("1.0"), accepted("<1.1"))
        assertEquals(listOf("1.1"), accepted("=1.1"))
        assertEquals(listOf("1.1"), accepted("==1.1"))
        assertEquals(listOf("1.0", "1.2"), accepted("!=1.1"))
        assertEquals("core=1.1", HollowAddonDependency.parse("core==1.1").toString())
    }

    @Test
    fun `a dependency that cannot be read is refused with the entry named`() {
        fun failure(text: String) =
            assertFailsWith<IllegalArgumentException> { read(text, RuntimePlatform.NEOFORGE) }.message.orEmpty()

        assertTrue("'library>='" in failure("id=a\ndependsOn=library>="))
        assertTrue("'>=1.2' does not start with an id" in failure("id=a\ndependsOn=>=1.2"))
        assertTrue("'library~1.2'" in failure("id=a\ndependsOnMods=library~1.2"))
        assertTrue("only dependsOnMods can be optional" in failure("id=a\ndependsOn=library?"))
    }

    @Test
    fun `versions compare by numbers with pre-releases below their release`() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-beta2", "1.0.0-beta10", "1.0.0-rc1", "1.0.0", "1.0.1", "1.10.0", "2.0")
        ordered.zipWithNext().forEach { (older, newer) ->
            assertTrue(AddonVersions.compare(older, newer) < 0, "$older should be older than $newer")
            assertTrue(AddonVersions.compare(newer, older) > 0, "$newer should be newer than $older")
        }
        assertEquals(0, AddonVersions.compare("1.0", "1.0.0"))
        assertEquals(0, AddonVersions.compare("1.0.0+build.7", "1.0.0"))
    }

    @Test
    fun `of two copies the newer wins and equal versions go to the earlier source`() {
        assertTrue(AddonVersions.compareCopies("2.0.0", 5, "1.0.0", 0) < 0)
        assertTrue(AddonVersions.compareCopies("1.0.0", 0, "1.0.0", 1) < 0)
        assertTrue(AddonVersions.compareCopies("1.0.0", 1, "1.0.0", 0) > 0)
    }

    private fun read(text: String, platform: RuntimePlatform): HollowAddonDescriptor =
        HollowAddonDescriptorReader.read(text.byteInputStream(), platform)
}
