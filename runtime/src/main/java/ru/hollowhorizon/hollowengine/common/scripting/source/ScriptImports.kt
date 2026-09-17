package ru.hollowhorizon.hollowengine.common.scripting.source

/**
 * Resolution of `@file:Import(...)` references.
 *
 * The compiler resolves imports from the parsed annotations, the cache fingerprints them from the file
 * text; both end up here so a script and its cache key always agree on which files it is built from.
 */
object ScriptImports {
    private val ANNOTATION = Regex("""@(?:file:)?Import\s*\(([^)]*)\)""")
    private val STRING_LITERAL = Regex("\"([^\"\\n]*)\"")

    /** Import references written in [text], in source order. */
    fun parse(text: String): List<String> = ANNOTATION.findAll(text)
        .flatMap { match -> STRING_LITERAL.findAll(match.groupValues[1]) }
        .map { literal -> literal.groupValues[1].trim() }
        .filter(String::isNotEmpty)
        .toList()

    /**
     * Turns a reference written inside [owner] into a script id. Unqualified references are resolved
     * next to the importing script and then from the root of its namespace; qualified ones must point
     * at a declared dependency.
     */
    fun resolve(owner: ScriptId, reference: String): ScriptId? {
        val normalized = reference.replace('\\', '/').trim().removePrefix("/")
        if (normalized.isEmpty() || normalized.any(Char::isISOControl)) return null

        if (normalized.indexOf(ScriptId.SEPARATOR) >= 0) {
            val id = ScriptId.parse(normalized, owner.namespace)
            if (!ScriptRegistry.canImport(owner.namespace, id.namespace)) {
                throw IllegalArgumentException(
                    "Script ${ScriptRegistry.display(owner)} imports '$normalized', but '${owner.namespace}' " +
                        "does not declare '${id.namespace}' in dependsOn",
                )
            }
            return id.takeIf { ScriptRegistry.artifacts(it) != null }
        }

        val directory = owner.path.substringBeforeLast('/', "")
        val neighbour = if (directory.isEmpty()) normalized else "$directory/$normalized"
        return sequenceOf(neighbour, normalized)
            .map { path -> ScriptId(owner.namespace, normalize(path)) }
            .firstOrNull { candidate -> ScriptRegistry.artifacts(candidate) != null }
    }

    /**
     * Every script [root] is built from, itself included, in a stable order and without revisiting a
     * script reached through several import paths.
     */
    fun closure(root: ScriptId): List<ScriptId> {
        val ordered = LinkedHashSet<ScriptId>()
        fun visit(id: ScriptId) {
            if (!ordered.add(id)) return
            val source = ScriptRegistry.artifacts(id)?.sourceFile?.takeIf(java.io.File::isFile) ?: return
            parse(ScriptText.normalize(source.readText())).forEach { reference ->
                runCatching { resolve(id, reference) }.getOrNull()?.let(::visit)
            }
        }
        visit(root)
        return ordered.toList()
    }

    /** Collapses `.` and `..` segments so two spellings of one path share a cache entry. */
    private fun normalize(path: String): String {
        val segments = ArrayList<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        return segments.joinToString("/")
    }
}
