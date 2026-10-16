package ru.hollowhorizon.hollowengine.client.ui.style

import ru.hollowhorizon.hollowengine.client.ui.*

data class HssDocument(
    val rules: List<HssRule>,
    val keyframes: List<HssKeyframes> = emptyList(),
    val imports: List<HssImport> = emptyList(),
    val variables: List<HssVariable> = emptyList(),
    val sets: List<HssDeclarationSet> = emptyList(),
)

/**
 * `$name { declarations }` at the top of a stylesheet: declarations a rule takes in with
 * `@apply $name;`, the way several rules share one look (a scrollbar, a popup frame).
 */
data class HssDeclarationSet(
    val name: String,
    val declarations: List<HssDeclaration>,
    val nameStart: Int = -1,
)

/** `@import "namespace:path.hss";`: the variables and rules of another stylesheet, taken in first. */
data class HssImport(
    val location: String,
    val start: Int = -1,
    val end: Int = -1,
)

/**
 * `$name: value;` at the top of a stylesheet. Any value, a variable's included, may name it as
 * `$name`, and the name is replaced by the value before the declaration is compiled.
 */
data class HssVariable(
    val name: String,
    val value: String,
    val nameStart: Int = -1,
    val valueStart: Int = -1,
)

data class HssRule(
    val selectors: List<HssSelector>,
    val declarations: List<HssDeclaration>,
    val order: Int,
)

/**
 * A `property: value` pair. [propertyStart] and [valueStart] are source offsets when the
 * declaration came from a parsed document, and `-1` when it was built in code; the IDE
 * uses them to place diagnostics and inlay hints on the exact token.
 */
data class HssDeclaration(
    val property: String,
    val value: String,
    val propertyStart: Int = -1,
    val valueStart: Int = -1,
) {
    val propertyRange: IntRange? get() = rangeAt(propertyStart, property.length)
    val valueRange: IntRange? get() = rangeAt(valueStart, value.length)

    private fun rangeAt(start: Int, length: Int): IntRange? =
        if (start < 0) null else start until (start + length).coerceAtLeast(start + 1)

    val isApply: Boolean get() = property == APPLY

    companion object {
        const val APPLY = "@apply"
    }
}

data class HssKeyframes(
    val name: String,
    val frames: List<HssKeyframe>,
    val nameStart: Int = -1,
)

data class HssKeyframe(
    val offsets: List<Float>,
    val declarations: List<HssDeclaration>,
)

data class HssSelector(
    val type: String? = null,
    val id: String? = null,
    val tags: Set<String> = emptySet(),
    val states: Set<UiState> = emptySet(),
    val attributes: Set<HssAttributeSelector> = emptySet(),
    val ancestor: HssSelector? = null,
) {
    val specificity: Int = (ancestor?.specificity
        ?: 0) + (if (id != null) 100 else 0) + (tags.size + states.size + attributes.size) * 10 + if (type != null) 1 else 0

    /**
     * Whether the rule only applies while some node is in a state, the matched node itself
     * (`.button:hover`) or one of its ancestors (`.button:hover .icon`).
     */
    val stateDependent: Boolean = states.isNotEmpty() || ancestor?.stateDependent == true

    fun matches(node: UiNode): Boolean {
        if (!matchesSelf(node)) return false
        val requiredAncestor = ancestor ?: return true
        var current = node.layoutState.parentNode
        while (current != null) {
            if (requiredAncestor.matches(current)) return true
            current = current.layoutState.parentNode
        }
        return false
    }

    private fun matchesSelf(node: UiNode): Boolean {
        if (type != null && node.type != type) return false
        if (id != null && node.id != id) return false
        if (!node.tags.containsAll(tags)) return false
        if (!node.hasEffectiveStates(states)) return false
        if (attributes.any { !it.matches(node) }) return false
        return true
    }
}

data class HssAttributeSelector(
    val name: String,
    val value: String? = null,
) {
    fun matches(node: UiNode): Boolean {
        if (value == null) return node.hasAttribute(name)
        return node.attributeValue(name) == value
    }
}

/**
 * A syntax error with the exact source range it applies to. The range covers the token
 * that is actually wrong, the missing value instead of whatever character the parser
 * happened to stop on after skipping whitespace.
 */
data class HssParseException(
    val messageText: String,
    val position: Int,
    val endPosition: Int = position + 1,
) : IllegalArgumentException("$messageText at $position")
