package ru.hollowhorizon.hollowengine.addons.narrative.graph

/**
 * The Kotlin package of each story. Stories import one another, and every one of them has its `main` and variables of
 * its own names, which only a package of its own tells apart.
 */
object StoryPackages {
    private const val ROOT = "story"
    private const val EXTENSION = ".story"
    private val FORBIDDEN = Regex("""[^\p{L}\p{N}_]""")

    /** The package of the story that the qualified script id [file], `namespace:path/name.story`, names. */
    fun of(file: String): String {
        val namespace = file.substringBefore(':')
        val path = file.substringAfter(':').removeSuffix(EXTENSION)
        return (listOf(ROOT, namespace) + path.split('/')).joinToString(".") { "`${FORBIDDEN.replace(it, "_")}`" }
    }
}
