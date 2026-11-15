package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonVersions

/**
 * One entry of `dependsOn` or `dependsOnMods`: `library`, `library>=1.2`, `library>=1.2<2.0`, and for
 * mods also `jei?` or `jei>=19?`, where the question mark makes the dependency optional.
 *
 * An optional dependency does not have to be installed; when it is, its version still has to fit.
 */
data class HollowAddonDependency(
    val id: String,
    val versions: HollowVersionRange = HollowVersionRange.ANY,
    val optional: Boolean = false,
) {
    fun accepts(version: String): Boolean = versions.accepts(version)

    /** The entry as a descriptor spells it. */
    override fun toString(): String = id + versions + if (optional) "?" else ""

    companion object {
        private val idPattern = Regex("[a-z0-9_.-]+")
        private val boundPattern = Regex("(>=|<=|==|!=|>|<|=)([^<>=!]+)")

        /** @throws IllegalArgumentException with what is wrong with [spec] */
        fun parse(spec: String): HollowAddonDependency {
            val text = spec.trim()
            val optional = text.endsWith('?')
            val body = text.removeSuffix("?").trim()
            val idEnd = body.indexOfFirst { it in "<>=!" }.takeIf { it >= 0 } ?: body.length
            val id = body.substring(0, idEnd).trim()
            require(id.matches(idPattern)) { "'$spec' does not start with an id" }

            val constraints = body.substring(idEnd)
            val bounds = boundPattern.findAll(constraints).map { match ->
                val version = match.groupValues[2].trim()
                require(version.isNotEmpty()) { "'$spec' compares with an empty version" }
                HollowVersionRange.Bound(HollowVersionRange.Comparison.of(match.groupValues[1]), version)
            }.toList()
            require(boundPattern.replace(constraints, "").isBlank()) {
                "'$spec' is not an id followed by versions such as >=1.2 or >=1.2<2.0"
            }
            return HollowAddonDependency(id, HollowVersionRange(bounds), optional)
        }

        fun parseOrNull(spec: String): HollowAddonDependency? = runCatching { parse(spec) }.getOrNull()
    }
}

/** Versions a dependency may have: all of [bounds] hold, and no bounds means any version. */
data class HollowVersionRange(val bounds: List<Bound> = emptyList()) {
    val isAny: Boolean get() = bounds.isEmpty()

    fun accepts(version: String): Boolean = bounds.all { bound -> bound.accepts(version) }

    override fun toString(): String = bounds.joinToString("")

    data class Bound(val comparison: Comparison, val version: String) {
        fun accepts(candidate: String): Boolean = comparison.holds(AddonVersions.compare(candidate, version))

        override fun toString(): String = comparison.sign + version
    }

    enum class Comparison(val sign: String, val holds: (Int) -> Boolean) {
        AT_LEAST(">=", { it >= 0 }),
        AT_MOST("<=", { it <= 0 }),
        NEWER(">", { it > 0 }),
        OLDER("<", { it < 0 }),
        EXACTLY("=", { it == 0 }),
        NOT("!=", { it != 0 });

        companion object {
            fun of(sign: String): Comparison = if (sign == "==") EXACTLY else entries.first { it.sign == sign }
        }
    }

    companion object {
        val ANY = HollowVersionRange()
    }
}

/** The ids, which is all that most of the engine needs to know about a list of dependencies. */
internal val List<HollowAddonDependency>.ids: List<String> get() = map(HollowAddonDependency::id)

/**
 * Ids of the mods worth looking for: every required one, and the optional ones that are installed.
 * Whoever compiles scripts against them would otherwise report a missing optional mod as a problem.
 */
internal fun List<HollowAddonDependency>.present(isInstalled: (String) -> Boolean): List<String> =
    filter { dependency -> !dependency.optional || isInstalled(dependency.id) }.ids

/** [ids] as dependencies, each keeping the versions and optionality it already has in this list. */
internal fun List<HollowAddonDependency>.withIds(ids: List<String>): List<HollowAddonDependency> {
    val known = associateBy(HollowAddonDependency::id)
    return ids.distinct().map { id -> known[id] ?: HollowAddonDependency(id) }
}
