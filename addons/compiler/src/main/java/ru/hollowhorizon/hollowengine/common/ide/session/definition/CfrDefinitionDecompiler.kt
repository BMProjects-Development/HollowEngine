package ru.hollowhorizon.hollowengine.common.ide.session.definition

import com.intellij.openapi.vfs.VirtualFile
import org.objectweb.asm.ClassReader
import ru.hollowhorizon.hollowengine.common.scripting.ide.DefinitionLocation
import ru.hollowhorizon.hollowengine.common.scripting.ide.SourceDocumentPath
import java.nio.file.Files
import kotlin.io.path.absolutePathString

internal class CfrDefinitionDecompiler {
    /** Recently opened classes only. Decompiled game class easily weighs hundreds of kilobytes. */
    private val cache = object : LinkedHashMap<String, String>(CacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > CacheSize
    }

    fun decompile(classFile: VirtualFile, target: SourceDeclarationIdentity, matcher: SourceDeclarationMatcher): DefinitionLocation? {
        val outerFile = classFile.outerClassFile()
        val text = text(outerFile) ?: return null
        return DefinitionLocation(
            path = SourceDocumentPath.Decompiled(ClassReader(outerFile.contentsToByteArray()).className).path,
            offset = matcher.offset("decompiled.java", text,
                target.copy(name = CfrSourceDecompiler.sourceIdentifier(target.name))) ?: return null,
            text = text,
            readOnly = true,
        )
    }

    /** Source of the outermost class [classFile] belongs to. */
    fun text(classFile: VirtualFile): String? {
        val outerFile = classFile.outerClassFile()
        if (!outerFile.path.endsWith(".class")) return null
        val key = "${outerFile.path}:${outerFile.modificationStamp}"
        cache[key]?.let { return it }
        return decompileClass(outerFile)?.also { cache[key] = it }
    }

    private fun VirtualFile.outerClassFile(): VirtualFile {
        val outerName = nameWithoutExtension.substringBefore('$')
        return parent?.findChild("$outerName.class") ?: this
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

private const val CacheSize = 20
