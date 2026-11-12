package ru.hollowhorizon.hollowengine.common.addons

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.*

class HollowAddonRuntimeTests {
    @TempDir
    lateinit var root: File

    private val addons get() = root.resolve("addons")
    private val mods get() = root.resolve("mods")
    private val cache get() = root.resolve("cache")
    private val installedMods = mutableSetOf<String>()
    private val modVersions = mutableMapOf<String, String>()
    private val notifications = mutableListOf<String>()
    private lateinit var runtime: HollowAddonRuntime

    @BeforeTest
    fun prepare() {
        HollowAddonRuntimeEnvironment.platform = RuntimePlatform.NEOFORGE
        addons.mkdirs()
        mods.mkdirs()
    }

    @AfterTest
    fun close() {
        if (::runtime.isInitialized) runBlocking { runtime.close() }
    }

    @Test
    fun `the newest copy runs and the other one is named a duplicate`() {
        addon(addons, "old.jar", "id=library\nversion=1.0.0")
        addon(mods, "new.jar", "id=library\nversion=1.2.0-beta10")
        start()

        assertEquals(HollowAddonState.LOADED, state("mods/new.jar"))
        assertEquals(HollowAddonState.DUPLICATE, state("addons/old.jar"))
        assertEquals(listOf("1.2.0-beta10"), runtime.loadedSnapshot.map { it.version })
    }

    @Test
    fun `equal versions are settled by the order of the sources`() {
        addon(mods, "library.jar", "id=library\nversion=1.0.0")
        addon(addons, "library.jar", "id=library\nversion=1.0.0")
        start()

        assertEquals(HollowAddonState.LOADED, state("addons/library.jar"))
        assertEquals(HollowAddonState.DUPLICATE, state("mods/library.jar"))
    }

    @Test
    fun `an addon with two copies can still be disabled and enabled`() = runBlocking {
        addon(addons, "a.jar", "id=library\nversion=1.0.0")
        addon(mods, "b.jar", "id=library\nversion=2.0.0")
        start()

        assertTrue(runtime.setEnabled("library", false).successful)
        assertTrue(runtime.loadedSnapshot.isEmpty())
        assertEquals(setOf(HollowAddonState.DISABLED), runtime.statusSnapshot.map { it.state }.toSet())

        assertTrue(runtime.setEnabled("library", true).successful)
        assertEquals(HollowAddonState.LOADED, state("mods/b.jar"))
    }

    @Test
    fun `removing the running copy starts the one that was shadowed`() = runBlocking {
        addon(addons, "old.jar", "id=library\nversion=1.0.0")
        val newer = stage(addon(mods, "new.jar", "id=library\nversion=2.0.0"))
        start()

        runtime.remove(newer)

        assertEquals(HollowAddonState.LOADED, state("addons/old.jar"))
        assertEquals(listOf("1.0.0"), runtime.loadedSnapshot.map { it.version })
    }

    @Test
    fun `a newer copy dropped in while the game runs takes over as it would after a restart`() = runBlocking {
        addon(mods, "old.jar", "id=library\nversion=1.0.0")
        start()

        runtime.install(stage(addon(addons, "new.jar", "id=library\nversion=1.1.0")))

        assertEquals(HollowAddonState.LOADED, state("addons/new.jar"))
        assertEquals(HollowAddonState.DUPLICATE, state("mods/old.jar"))
    }

    @Test
    fun `a copy made for the other loader never shadows the one that can run`() {
        addon(addons, "library-fabric.jar", "id=library\nversion=2.0.0\nplatform=fabric")
        addon(addons, "library-neoforge.jar", "id=library\nversion=2.0.0\nplatform=neoforge")
        start()

        assertEquals(HollowAddonState.LOADED, state("addons/library-neoforge.jar"))
        val other = status("addons/library-fabric.jar")
        assertEquals(HollowAddonState.INCOMPATIBLE, other.state)
        assertEquals("Made for fabric; this game runs neoforge.", other.details)
    }

    @Test
    fun `an addon for the other side is inactive and is not offered to scripts`() {
        addon(addons, "hud.jar", "id=hud\nenvironment=client")
        addon(addons, "quests.jar", "id=quests")
        start()

        assertEquals(HollowAddonState.INACTIVE, state("addons/hud.jar"))
        assertEquals(listOf("quests"), runtime.enabledSnapshot.map { it.descriptor.id })
    }

    @Test
    fun `a missing mod keeps the addon from loading and is named`() {
        installedMods += "jei"
        addon(addons, "compat.jar", "id=compat\ndependsOnMods=jei,create")
        addon(
            addons, "neoforge-only.jar",
            "id=bridge\nplatform=fabric,neoforge\ndependsOnMods.neoforge=jei\ndependsOnMods.fabric=fabric-api",
        )
        start()

        val compat = status("addons/compat.jar")
        assertEquals(HollowAddonState.MISSING_MODS, compat.state)
        assertEquals("Requires mods that are not installed: create.", compat.details)
        assertEquals(HollowAddonState.LOADED, state("addons/neoforge-only.jar"))
    }

    @Test
    fun `an optional mod may be absent, but an installed one has to be of a version that fits`() {
        modVersions["jei"] = "18.2.0"
        addon(addons, "absent.jar", "id=absent\ndependsOnMods=create?,curios>=5?")
        addon(addons, "fits.jar", "id=fits\ndependsOnMods=jei>=18<19")
        addon(addons, "old.jar", "id=old\ndependsOnMods=jei>=19?")
        start()

        assertEquals(HollowAddonState.LOADED, state("addons/absent.jar"))
        assertEquals(HollowAddonState.LOADED, state("addons/fits.jar"))
        val old = status("addons/old.jar")
        assertEquals(HollowAddonState.MISSING_MODS, old.state)
        assertEquals("Requires other versions of mods: jei>=19? (18.2.0 is installed).", old.details)
    }

    @Test
    fun `an addon waits for the version of a dependency it asks for`() = runBlocking {
        addon(addons, "library.jar", "id=library\nversion=1.0.0")
        addon(addons, "quests.jar", "id=quests\ndependsOn=library>=1.2")
        start()

        val quests = status("addons/quests.jar")
        assertEquals(HollowAddonState.WAITING_FOR_DEPENDENCIES, quests.state)
        assertEquals("Needs: library>=1.2 (1.0.0 is running).", quests.details)

        runtime.install(stage(addon(addons, "library.jar", "id=library\nversion=1.2.0")))

        assertEquals(listOf("library", "quests"), runtime.loadedSnapshot.map { it.id })
    }

    @Test
    fun `the player is told what starts and stops after the game is up, and what cannot run`() = runBlocking {
        addon(addons, "library.jar", "id=library\nversion=1.0.0")
        addon(addons, "broken.jar", "id=broken\nrequiredClasses=does.not.Exist")
        addon(addons, "compat.jar", "id=compat\ndependsOnMods=create")
        addon(addons, "hud.jar", "id=hud\nenvironment=client")
        start()
        // A missing mod is the player's to fix; an addon for the other side is nothing to report.
        assertEquals(listOf("not running: broken failed", "not running: compat missing mods"), notifications)
        notifications.clear()

        runtime.install(stage(addon(addons, "quests.jar", "id=quests\ndependsOn=library")))
        addon(addons, "library.jar", "id=library\nversion=1.1.0")
        assertTrue(runtime.reload("library").successful)
        assertTrue(runtime.setEnabled("library", false).successful)

        assertEquals(
            listOf(
                "loaded: quests 1.0.0",
                "reloaded: library 1.1.0, quests 1.0.0",
                "not running: quests waiting for dependencies",
                "unloaded: library 1.1.0, quests 1.0.0",
            ),
            notifications,
        )
    }

    @Test
    fun `a dependency reloaded in a version that no longer fits stops its dependant`() = runBlocking {
        addon(addons, "library.jar", "id=library\nversion=1.2.0")
        addon(addons, "quests.jar", "id=quests\ndependsOn=library>=1.2")
        start()
        assertEquals(listOf("library", "quests"), runtime.loadedSnapshot.map { it.id })

        addon(addons, "library.jar", "id=library\nversion=1.0.0")
        assertTrue(runtime.reload("library").successful)

        assertEquals(listOf("library"), runtime.loadedSnapshot.map { it.id })
        assertEquals("Needs: library>=1.2 (1.0.0 is running).", status("addons/quests.jar").details)
    }

    @Test
    fun `an addon switched off stays off after the game restarts`() = runBlocking {
        addon(addons, "library.jar", "id=library")
        start()
        assertTrue(runtime.setEnabled("library", false).successful)
        runtime.close()

        start()

        assertEquals(HollowAddonState.DISABLED, state("addons/library.jar"))
        assertTrue(runtime.loadedSnapshot.isEmpty())
    }

    @Test
    fun `a waiting addon says what each dependency is missing for`() {
        addon(addons, "quests.jar", "id=quests\ndependsOn=library,absent,broken")
        addon(addons, "library.jar", "id=library")
        addon(addons, "broken.jar", "id=broken\nrequiredClasses=does.not.Exist")
        addon(addons, "left.jar", "id=left\ndependsOn=right")
        addon(addons, "right.jar", "id=right\ndependsOn=left")
        start()

        val quests = status("addons/quests.jar")
        assertEquals(HollowAddonState.WAITING_FOR_DEPENDENCIES, quests.state)
        assertEquals("Needs: absent (not installed), broken (failed).", quests.details)
        assertEquals(HollowAddonState.FAILED, state("addons/broken.jar"))
        assertEquals("Needs: right (depends back on 'left').", status("addons/left.jar").details)
    }

    @Test
    fun `disabling an addon stops its dependants and enabling it brings them back`() = runBlocking {
        addon(addons, "library.jar", "id=library")
        addon(addons, "quests.jar", "id=quests\ndependsOn=library")
        start()
        assertEquals(listOf("library", "quests"), runtime.loadedSnapshot.map { it.id })

        val disabled = runtime.setEnabled("library", false)
        assertTrue(disabled.successful)
        assertTrue("quests" in disabled.message, disabled.message)
        assertEquals("Needs: library (disabled).", status("addons/quests.jar").details)

        assertTrue(runtime.setEnabled("library", true).successful)
        assertEquals(listOf("library", "quests"), runtime.loadedSnapshot.map { it.id })
    }

    @Test
    fun `an update that fails to start leaves the previous version running and says why`() = runBlocking {
        val file = addon(addons, "library.jar", "id=library\nversion=1.0.0")
        addon(addons, "quests.jar", "id=quests\ndependsOn=library")
        start()

        runtime.install(stage(addon(addons, "library.jar", "id=library\nversion=2.0.0\nrequiredClasses=does.not.Exist")))

        val status = status("addons/library.jar")
        assertEquals(HollowAddonState.LOADED, status.state)
        assertEquals("1.0.0", status.descriptor.version)
        assertTrue("2.0.0" in status.details.orEmpty() && "does.not.Exist" in status.details.orEmpty(), status.details)
        assertEquals(listOf("library", "quests"), runtime.loadedSnapshot.map { it.id })
        assertTrue(file.isFile)
    }

    @Test
    fun `installing the file that already runs does not restart the addon`() = runBlocking {
        val file = addon(addons, "library.jar", "id=library")
        start()
        val loaded = runtime.loadedSnapshot

        runtime.install(stage(file))

        assertSame(loaded, runtime.loadedSnapshot)
    }

    @Test
    fun `reload restarts the addon and reports a jar it cannot read`() = runBlocking {
        val file = addon(addons, "library.jar", "id=library\nversion=1.0.0")
        start()

        addon(addons, "library.jar", "id=library\nversion=1.1.0")
        val reloaded = runtime.reload("library")
        assertTrue(reloaded.successful, reloaded.message)
        assertEquals(listOf("1.1.0"), runtime.loadedSnapshot.map { it.version })

        file.writeText("not a jar")
        val unreadable = runtime.reload("library")
        assertFalse(unreadable.successful)
        assertEquals(listOf("1.1.0"), runtime.loadedSnapshot.map { it.version })
        assertEquals(listOf("addons/library.jar"), runtime.problemSnapshot.map { it.fileName })
    }

    @Test
    fun `a jar that cannot be read at startup is listed as a problem`() {
        addon(addons, "typo.jar", "id=typo\nplatform=forge")
        addon(addons, "library.jar", "id=library\nentry.forge=com.example.Entry")
        start()

        assertTrue(runtime.statusSnapshot.isEmpty())
        val problems = runtime.problemSnapshot.associate { it.fileName to it.message }
        assertTrue("unknown platform 'forge'" in problems.getValue("addons/typo.jar"), problems.toString())
        assertTrue("unknown loader 'forge'" in problems.getValue("addons/library.jar"), problems.toString())
    }

    @Test
    fun `an update that needs a restart does not stop the running version`() = runBlocking {
        addon(addons, "media.jar", "id=media\nversion=1.0.0")
        addon(addons, "player.jar", "id=player\ndependsOn=media")
        start()

        runtime.install(stage(addon(addons, "media.jar", "id=media\nversion=2.0.0", NATIVE_LIBRARY)))
        runtime.install(stage(addon(addons, "physics.jar", "id=physics", NATIVE_LIBRARY)))

        val media = status("addons/media.jar")
        assertEquals(HollowAddonState.LOADED, media.state)
        assertEquals("1.0.0", media.descriptor.version)
        assertTrue("restart Minecraft" in media.details.orEmpty(), media.details)
        assertEquals(HollowAddonState.RESTART_REQUIRED, state("addons/physics.jar"))
        assertEquals(listOf("media", "physics"), runtime.restartRequiredSnapshot.map { it.id })
        assertEquals(listOf("media", "player"), runtime.loadedSnapshot.map { it.id })
    }

    @Test
    fun `the watcher installs a jar once it has stopped changing and removes it with its file`() = runBlocking {
        start()
        val watcher = HollowAddonWatcher(addons, HollowAddonArtifactStore(cache), runtime, this)

        val file = addon(addons, "library.jar", "id=library\nversion=1.0.0")
        watcher.scan()
        assertTrue(runtime.loadedSnapshot.isEmpty(), "a jar seen once may still be copied")
        watcher.scan()
        assertEquals(listOf("1.0.0"), runtime.loadedSnapshot.map { it.version })

        addon(addons, "library.jar", "id=library\nversion=1.1.0")
        file.setLastModified(file.lastModified() + 10_000)
        watcher.scan()
        watcher.scan()
        assertEquals(listOf("1.1.0"), runtime.loadedSnapshot.map { it.version })

        file.writeText("half of a jar")
        watcher.scan()
        watcher.scan()
        assertEquals(listOf("1.1.0"), runtime.loadedSnapshot.map { it.version })
        assertEquals(listOf("addons/library.jar"), runtime.problemSnapshot.map { it.fileName })

        assertTrue(file.delete())
        watcher.scan()
        assertTrue(runtime.loadedSnapshot.isEmpty())
        assertTrue(runtime.statusSnapshot.isEmpty())
        assertTrue(runtime.problemSnapshot.isEmpty())
    }

    private fun start() {
        runtime = HollowAddonRuntime(
            sources = listOf(addons, mods),
            cacheDirectory = cache,
            addonsDirectory = addons,
            modsDirectory = mods,
            isModLoaded = { id -> id in installedMods || id in modVersions },
            modVersion = modVersions::get,
            notifier = object : HollowAddonNotifier {
                private fun List<HollowAddonDescriptor>.names() = joinToString { "${it.id} ${it.version}" }
                override fun loaded(addons: List<HollowAddonDescriptor>) { notifications += "loaded: ${addons.names()}" }
                override fun reloaded(addons: List<HollowAddonDescriptor>) { notifications += "reloaded: ${addons.names()}" }
                override fun unloaded(addons: List<HollowAddonDescriptor>) { notifications += "unloaded: ${addons.names()}" }
                override fun notRunning(status: HollowAddonStatus) {
                    notifications += "not running: ${status.descriptor.id} ${status.state.displayName}"
                }
                override fun restartRequired(descriptor: HollowAddonDescriptor) { notifications += "restart: ${descriptor.id}" }
            },
            embeddedMods = { emptyList() },
            watchSources = false,
        )
        runBlocking { runtime.start() }
    }

    private fun stage(file: File): HollowAddonCandidate = HollowAddonArtifactStore(cache).stage(file)

    private fun status(fileName: String): HollowAddonStatus = runtime.statusSnapshot.single { it.fileName == fileName }

    private fun state(fileName: String): HollowAddonState = status(fileName).state

    private fun addon(directory: File, name: String, descriptor: String, vararg emptyEntries: String): File {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue(HollowAddonLayout.FORMAT_ATTRIBUTE, HollowAddonLayout.CURRENT_FORMAT)
        }
        val file = directory.resolve(name)
        JarOutputStream(file.outputStream(), manifest).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/plugin.properties"))
            jar.write(descriptor.toByteArray())
            jar.closeEntry()
            emptyEntries.forEach { name ->
                jar.putNextEntry(JarEntry(name))
                jar.closeEntry()
            }
        }
        return file
    }

    private companion object {
        const val NATIVE_LIBRARY = "hollowengine-addon-bootstrap/native.jar"
    }
}
