package ru.hollowhorizon.hollowengine.runtime.remap

/**
 * Moves whole packages, so both loaders' copies of the shared bootstrap can live in one jar.
 */
class PrefixRelocation(rules: Map<String, String>) {
    private val rules = rules.entries
        .map { (from, to) -> from.replace('.', '/') to to.replace('.', '/') }
        .sortedByDescending { it.first.length }

    val isEmpty: Boolean get() = rules.isEmpty()

    fun reversed(): PrefixRelocation = PrefixRelocation(rules.associate { (from, to) -> to to from })

    fun relocate(internalName: String): String {
        for ((from, to) in rules) {
            if (internalName == from || internalName.startsWith("$from/")) {
                return to + internalName.substring(from.length)
            }
        }
        return internalName
    }

    fun relocateText(text: String): String = rules.fold(text) { current, (from, to) ->
        current
            .replace(from, to)
            .replace(from.replace('/', '.'), to.replace('/', '.'))
    }

    companion object {
        val NONE = PrefixRelocation(emptyMap())

        fun parse(value: String?): PrefixRelocation {
            if (value.isNullOrBlank()) return NONE
            return PrefixRelocation(
                value.split(',')
                    .filter(String::isNotBlank)
                    .associate { rule ->
                        val (from, to) = rule.split('=', limit = 2)
                        from.trim() to to.trim()
                    }
            )
        }
    }
}
