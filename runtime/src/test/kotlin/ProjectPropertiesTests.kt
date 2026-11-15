package ru.hollowhorizon.hollowengine.common.addons.project

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonDependency
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonPlatform
import ru.hollowhorizon.hollowengine.common.addons.present
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectPropertiesTests {
    @Test
    fun `versions and optional mods survive a save and a read`() {
        val properties = read(
            "id=quests\nplatform=neoforge,fabric\ndependsOn=library>=1.2,core>=2<3\ndependsOnMods=jei?,create>=0.5",
        )
        assertEquals(HollowAddonPlatform.of(RuntimePlatform.FABRIC, RuntimePlatform.NEOFORGE), properties.platform)

        val again = ProjectProperties.read(properties.encode().inputStream())

        assertEquals(properties, again)
        assertEquals(listOf("library>=1.2", "core>=2<3"), again.addonRequirements.map { it.toString() })
        assertEquals(listOf("jei?", "create>=0.5"), again.modRequirements.map { it.toString() })
        assertEquals("fabric,neoforge", String(again.encode(), Charsets.ISO_8859_1).lineSequence()
            .single { it.startsWith("platform=") }.removePrefix("platform="))
    }

    @Test
    fun `a common project writes no platform`() {
        val text = String(read("id=quests").encode(), Charsets.ISO_8859_1)
        assertNull(text.lineSequence().firstOrNull { it.startsWith("platform=") })
    }

    @Test
    fun `choosing dependencies in the settings keeps the versions of those that stay`() {
        val properties = read("id=quests\ndependsOn=library>=1.2,old\ndependsOnMods=jei>=19?")

        val edited = properties.withDependencies(addons = listOf("library", "fresh"), mods = listOf("jei", "create"))

        assertEquals(listOf("library>=1.2", "fresh"), edited.addonRequirements.map { it.toString() })
        assertEquals(listOf("jei>=19?", "create"), edited.modRequirements.map { it.toString() })
    }

    @Test
    fun `an entry that cannot be read does not keep the project from opening`() {
        val properties = read("id=quests\ndependsOn=library>=,core\ndependsOnMods=jei~1")
        assertEquals(listOf("core"), properties.dependsOn)
        assertEquals(emptyList(), properties.modDependencies)
    }

    @Test
    fun `scripts are compiled against required mods and the optional ones that are installed`() {
        val mods = listOf("jei?", "create", "curios>=5?").map(HollowAddonDependency::parse)
        assertEquals(listOf("create", "curios"), mods.present { it == "curios" })
    }

    @Test
    fun `the loader scan finds classes of either loader and none in plain Minecraft code`() {
        assertEquals("net.neoforged.bus.api.IEventBus", LoaderApiScan.firstIn(classUsing("net/neoforged/bus/api/IEventBus")))
        assertEquals("net.fabricmc.api.ModInitializer", LoaderApiScan.firstIn(classUsing("net/fabricmc/api/ModInitializer")))
        assertNull(LoaderApiScan.firstIn(classUsing("net/minecraft/core/BlockPos")))
    }

    /** A class with one method taking a parameter of [type]. */
    private fun classUsing(type: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "Script", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "(L$type;)V", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun read(text: String) = ProjectProperties.read(text.byteInputStream())
}
