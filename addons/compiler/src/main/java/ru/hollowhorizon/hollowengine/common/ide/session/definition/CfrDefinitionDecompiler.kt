package ru.hollowhorizon.hollowengine.common.ide.session.definition

import com.intellij.openapi.vfs.VirtualFile
import org.objectweb.asm.ClassReader
import ru.hollowhorizon.hollowengine.common.scripting.ide.DefinitionLocation
import java.nio.file.Files
import kotlin.io.path.absolutePathString

internal class CfrDefinitionDecompiler {
    private val cache = linkedMapOf<String, String>()

    fun decompile(classFile: VirtualFile, target: SourceDeclarationIdentity, matcher: SourceDeclarationMatcher): DefinitionLocation? {
        val outerName = classFile.nameWithoutExtension.substringBefore('$')
        val outerFile = classFile.parent?.findChild("$outerName.class") ?: classFile
        val classPath = outerFile.path
        if (!classPath.endsWith(".class")) return null
        val key = "$classPath:${outerFile.modificationStamp}"
        val text = cache.getOrPut(key) {
            decompileClass(outerFile) ?: return null
        }
        return DefinitionLocation(
            path = "decompiled/${ClassReader(outerFile.contentsToByteArray()).className}.java",
            offset = matcher.offset("decompiled.java", text, target) ?: return null,
            text = text,
            readOnly = true,
        )
    }

    /**
     * Copies [classFile] and its nested classes out of whatever file system holds them, a jar or the
     * JDK's module image, into a directory CFR can read, and decompiles the outer class there.
     */
    private fun decompileClass(classFile: VirtualFile): String? {
        val entryName = ClassReader(classFile.contentsToByteArray()).className + ".class"
        val outerClass = classFile.nameWithoutExtension.substringBefore('$')
        val siblings = classFile.parent?.children.orEmpty()
            .filter { it.extension == "class" && it.nameWithoutExtension.substringBefore('$') == outerClass }
        val tempRoot = Files.createTempDirectory("hollowengine-cfr-")
        return try {
            val packageDirectory = tempRoot.resolve(entryName).parent
            Files.createDirectories(packageDirectory)
            siblings.forEach { sibling -> Files.write(packageDirectory.resolve(sibling.name), sibling.contentsToByteArray()) }
            CfrSourceDecompiler.decompile(tempRoot.resolve(entryName).absolutePathString())
        } finally {
            tempRoot.toFile().deleteRecursively()
        }
    }
}

