package ru.hollowhorizon.hollowengine.common.scripting.nodes

import ru.hollowhorizon.hollowengine.common.scripting.DefaultScriptDefinitions
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.io.File
import java.util.concurrent.ConcurrentHashMap


object NodeAttachTargets {
    private val ATTACH = Regex("""@file\s*:\s*Attach\s*\(\s*([\w.`]+)\s*::\s*class\s*\)""")
    private val IMPORT = Regex("""^\s*import\s+((?:[\w`]+\.)*(?:[\w`]+|\*))(?:\s+as\s+([\w`]+))?""")
    private const val HEADER_LINES = 120
    private val cache = ConcurrentHashMap<String, CachedTarget>()

    private data class CachedTarget(val stamp: Long, val classLoader: ClassLoader, val target: AttachTarget)

    private data class ImportCandidate(val fqName: String, val alias: String?) {
        val wildcardPackage: String?
            get() = fqName.removeSuffix(".*").takeIf { fqName.endsWith(".*") }

        fun resolve(typeName: String): String? {
            if (wildcardPackage != null) return null
            val firstSegment = typeName.substringBefore('.')
            val importedName = alias ?: fqName.substringAfterLast('.')
            if (firstSegment != importedName) return null
            return fqName + typeName.removePrefix(firstSegment)
        }
    }

    sealed interface AttachTarget {
        data class Host(val fqName: String, val type: Class<*>?) : AttachTarget
        data object None : AttachTarget
        data object Unknown : AttachTarget
    }

    fun of(id: ScriptId): AttachTarget {
        val file = ScriptRegistry.artifacts(id)?.sourceFile ?: return AttachTarget.Unknown
        val stamp = runCatching { file.lastModified() }.getOrDefault(0L)
        val classLoader = ScriptRegistry.classLoader(id.namespace) ?: NodeAttachTargets::class.java.classLoader
        val key = file.path
        cache[key]?.takeIf { it.stamp == stamp && it.classLoader === classLoader }?.let { return it.target }
        val target = read(file, id.fileName, classLoader)
        cache[key] = CachedTarget(stamp, classLoader, target)
        return target
    }

    fun accepts(id: ScriptId, entityType: Class<*>): Boolean = when (val target = of(id)) {
        is AttachTarget.Host -> target.type?.isAssignableFrom(entityType) ?: true
        AttachTarget.None -> false
        AttachTarget.Unknown -> true
    }

    private fun read(file: File, fileName: String, classLoader: ClassLoader): AttachTarget = runCatching {
        val header = file.useLines { lines -> lines.take(HEADER_LINES).toList() }
        val declared =
            header.firstNotNullOfOrNull { ATTACH.find(it)?.groupValues?.get(1) }
                ?.replace("`", "")
                ?: return@runCatching AttachTarget.None
        val imports = header.mapNotNull(::parseImport)
        val defaultImports = DefaultScriptDefinitions.providerFor(fileName)?.defaultImports.orEmpty()
            .map { ImportCandidate(it, null) }
        AttachTarget.Host(declared, resolve(declared, imports, defaultImports, classLoader))
    }.getOrDefault(AttachTarget.Unknown)

    private fun parseImport(line: String): ImportCandidate? {
        val match = IMPORT.find(line) ?: return null
        return ImportCandidate(
            fqName = match.groupValues[1].replace("`", ""),
            alias = match.groupValues[2].takeIf(String::isNotEmpty)?.replace("`", ""),
        )
    }

    private fun resolve(
        name: String,
        imports: List<ImportCandidate>,
        defaultImports: List<ImportCandidate>,
        classLoader: ClassLoader,
    ): Class<*>? {
        val candidates = imports + defaultImports
        candidates.firstNotNullOfOrNull { candidate ->
            candidate.resolve(name)?.let { classOrNull(it, classLoader) }
        }?.let { return it }

        classOrNull(name, classLoader)?.let { return it }

        return candidates.asSequence()
            .mapNotNull(ImportCandidate::wildcardPackage)
            .mapNotNull { packageName -> classOrNull("$packageName.$name", classLoader) }
            .distinct()
            .singleOrNull()
    }

    private fun classOrNull(fqName: String, classLoader: ClassLoader): Class<*>? {
        var candidate = fqName
        while (true) {
            runCatching { Class.forName(candidate, false, classLoader) }.getOrNull()?.let { return it }
            val separator = candidate.lastIndexOf('.')
            if (separator < 0) return null
            candidate = candidate.substring(0, separator) + '$' + candidate.substring(separator + 1)
        }
    }
}
