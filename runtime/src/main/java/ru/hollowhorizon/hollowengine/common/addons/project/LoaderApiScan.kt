package ru.hollowhorizon.hollowengine.common.addons.project

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import java.io.File
import java.util.jar.JarFile

/**
 * Looks into compiled scripts for classes of a mod loader. A project made for every loader may use
 * only Minecraft and the engine: whatever it takes from one loader is missing on the other.
 *
 * Scripts are compiled against the running game, where the loader is always on the classpath, so this
 * is the only place that can tell. What a loader patches into Minecraft's own classes is not seen.
 */
internal object LoaderApiScan {
    private val LOADER_PACKAGES = listOf("net/neoforged/", "net/minecraftforge/", "net/fabricmc/", "org/quiltmc/")

    /** A loader class the classes in [jar] refer to, as a Java name, or null when there is none. */
    fun firstIn(jar: File): String? = JarFile(jar, false).use { archive ->
        archive.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.firstNotNullOfOrNull { entry ->
            firstIn(archive.getInputStream(entry).use { it.readBytes() })
        }
    }

    fun firstIn(classBytes: ByteArray): String? {
        val seen = References()
        ClassReader(classBytes).accept(ClassRemapper(object : ClassVisitor(Opcodes.ASM9) {}, seen), ClassReader.SKIP_FRAMES)
        return seen.loaderClass?.replace('/', '.')
    }

    /** Every class name of a class file passes through a remapper, which is all this one is used for. */
    private class References : Remapper() {
        var loaderClass: String? = null

        override fun map(internalName: String): String {
            if (loaderClass == null && LOADER_PACKAGES.any(internalName::startsWith)) loaderClass = internalName
            return internalName
        }
    }
}
