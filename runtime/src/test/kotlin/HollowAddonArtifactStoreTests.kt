package ru.hollowhorizon.hollowengine.common.addons

import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import ru.hollowhorizon.hollowengine.runtime.remap.PayloadRemapTable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HollowAddonArtifactStoreTests {
    @Test
    fun `classes leave their nested jar, scripts join them and resources stay out`(@TempDir directory: File) {
        val addon = writeAddon(
            directory.resolve("addon.jar"),
            HollowAddonLayout.CLASSES_JAR to jarOf("com/example/Addon.class" to classBytes("com/example/Addon", "Ljava/lang/String;")),
            "scripts/quest.node.kts" to "// quest".toByteArray(),
            "assets/test-addon/lang/en_us.json" to "{}".toByteArray(),
        )

        val candidate = store(directory, HollowAddonMappingNamespace.OFFICIAL).stage(addon)

        assertTrue(candidate.hasAssets)
        assertFalse(candidate.hasData)
        JarFile(candidate.classesFile).use { classes ->
            assertNotNull(classes.getJarEntry("com/example/Addon.class"))
            assertNotNull(classes.getJarEntry("scripts/quest.node.kts"))
            assertNull(classes.getJarEntry(HollowAddonLayout.CLASSES_JAR))
            assertNull(classes.getJarEntry("assets/test-addon/lang/en_us.json"))
        }
    }

    @Test
    fun `on Fabric the table remaps classes and compiled scripts and restamps the scripts`(@TempDir directory: File) {
        val table = PayloadRemapTable(
            from = "named",
            to = "intermediary",
            classes = mapOf(LEVEL to INTERMEDIARY_LEVEL),
            methods = emptyMap(),
            fields = emptyMap(),
        )
        val addon = writeAddon(
            directory.resolve("addon.jar"),
            HollowAddonLayout.CLASSES_JAR to jarOf("com/example/Addon.class" to classBytes("com/example/Addon", "L$LEVEL;")),
            "META-INF/hollowengine/scripts/quest.node.kts.jar" to scriptArtifact(),
            HollowAddonLayout.REMAP_TABLE to ByteArrayOutputStream().also(table::write).toByteArray(),
        )

        val candidate = store(directory, HollowAddonMappingNamespace.INTERMEDIARY).stage(addon)

        JarFile(candidate.classesFile).use { classes ->
            assertEquals("L$INTERMEDIARY_LEVEL;", fieldDescriptor(classes.read("com/example/Addon.class")))

            JarInputStream(ByteArrayInputStream(classes.read("META-INF/hollowengine/scripts/quest.node.kts.jar"))).use { script ->
                assertEquals(FABRIC_RUNTIME, script.manifest.mainAttributes.getValue(ScriptCache.RUNTIME_ATTRIBUTE))
                assertEquals("hash", script.manifest.mainAttributes.getValue(ScriptCache.HASH_ATTRIBUTE))
                val entry = generateSequence { script.nextJarEntry }.first { it.name == "Quest.class" }
                assertNotNull(entry)
                assertEquals("L$INTERMEDIARY_LEVEL;", fieldDescriptor(script.readBytes()))
            }
        }
    }

    @Test
    fun `an addon of the previous format is refused`(@TempDir directory: File) {
        val addon = writeAddon(directory.resolve("old.jar"), format = "2")

        assertFailsWith<IllegalArgumentException> { store(directory, HollowAddonMappingNamespace.OFFICIAL).stage(addon) }
    }

    private fun store(directory: File, namespace: HollowAddonMappingNamespace) = HollowAddonArtifactStore(
        directory.resolve("cache"),
        if (namespace == HollowAddonMappingNamespace.INTERMEDIARY) RuntimePlatform.FABRIC else RuntimePlatform.NEOFORGE,
        namespace,
        runtimeIdentity = { FABRIC_RUNTIME },
    )

    private fun writeAddon(file: File, vararg entries: Pair<String, ByteArray>, format: String = "3"): File {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue(HollowAddonLayout.FORMAT_ATTRIBUTE, format)
        }
        JarOutputStream(file.outputStream(), manifest).use { jar ->
            (listOf("META-INF/plugin.properties" to "id=test-addon\n".toByteArray()) + entries).forEach { (name, bytes) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        return file
    }

    private fun jarOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        JarOutputStream(bytes).use { jar ->
            entries.forEach { (name, content) ->
                jar.putNextEntry(JarEntry(name))
                jar.write(content)
                jar.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun scriptArtifact(): ByteArray {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue(ScriptCache.HASH_ATTRIBUTE, "hash")
            mainAttributes.putValue(ScriptCache.RUNTIME_ATTRIBUTE, "neoforge/official/production")
        }
        val bytes = ByteArrayOutputStream()
        JarOutputStream(bytes, manifest).use { jar ->
            jar.putNextEntry(JarEntry("Quest.class"))
            jar.write(classBytes("Quest", "L$LEVEL;"))
            jar.closeEntry()
        }
        return bytes.toByteArray()
    }

    /** A class with one field of type [fieldType]. */
    private fun classBytes(name: String, fieldType: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC, "level", fieldType, null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun fieldDescriptor(bytes: ByteArray): String =
        ClassNode().also { ClassReader(bytes).accept(it, 0) }.fields.single().desc

    private fun JarFile.read(name: String): ByteArray = getInputStream(getJarEntry(name)).use { it.readBytes() }

    private companion object {
        const val LEVEL = "net/minecraft/world/level/Level"
        const val INTERMEDIARY_LEVEL = "net/minecraft/class_1937"
        const val FABRIC_RUNTIME = "fabric/intermediary/production"
    }
}
