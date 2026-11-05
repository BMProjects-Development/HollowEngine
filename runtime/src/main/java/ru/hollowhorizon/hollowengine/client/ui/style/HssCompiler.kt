package ru.hollowhorizon.hollowengine.client.ui.style

import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.UiNode
import ru.hollowhorizon.hollowengine.client.ui.layout.readOnlyIterator
import ru.hollowhorizon.hollowengine.client.ui.toStylePatch

data class CompiledHss(
    val rules: List<StyleRule>,
    val keyframes: Map<String, UiKeyframes> = emptyMap(),
) {
    private val index by lazy { HssRuleIndex(rules) }

    /** Everything here that looks at the state of an ancestor, as `.row:hover` does in `.row:hover .icon`. */
    internal val statefulAncestors: List<HssSelector> by lazy {
        rules.flatMap { it.selector.statefulAncestors() }.distinct()
    }

    /** Rules of [origin] that match [node], sourced from the index so only candidates are tested. */
    fun matching(node: UiNode, origin: StyleOrigin): List<StyleRule> =
        index.matching(node, origin)

    /**
     * Appends every matching rule (any origin) to [into] in one index walk, so the resolver
     * can partition base/state rules itself instead of walking the index once per origin.
     */
    fun matchingInto(node: UiNode, into: MutableList<StyleRule>) = index.matchingInto(node, into)

    internal fun matchingIntoProfiled(node: UiNode, into: MutableList<StyleRule>): Int =
        index.matchingIntoProfiled(node, into)
}

internal class HssRuleIndex(rules: List<StyleRule>) {
    private val byId = HashMap<String, MutableList<StyleRule>>()
    private val byTag = HashMap<String, MutableList<StyleRule>>()
    private val byType = HashMap<String, MutableList<StyleRule>>()
    private val universal = ArrayList<StyleRule>()

    init {
        for (rule in rules) {
            val selector = rule.selector
            when {
                selector.id != null -> byId.getOrPut(selector.id) { ArrayList() } += rule
                selector.tags.isNotEmpty() -> byTag.getOrPut(selector.tags.first()) { ArrayList() } += rule
                selector.type != null -> byType.getOrPut(selector.type) { ArrayList() } += rule
                else -> universal += rule
            }
        }
    }

    fun matching(node: UiNode, origin: StyleOrigin): List<StyleRule> {
        val matches = ArrayList<StyleRule>()
        node.id?.let { id -> appendMatching(byId[id], node, origin, matches) }
        val tags = node.tags.readOnlyIterator()
        while (tags.hasNext()) appendMatching(byTag[tags.next()], node, origin, matches)
        appendMatching(byType[node.type], node, origin, matches)
        appendMatching(universal, node, origin, matches)
        return matches.ifEmpty { emptyList() }
    }

    fun matchingInto(node: UiNode, into: MutableList<StyleRule>) {
        node.id?.let { id -> appendMatching(byId[id], node, origin = null, into) }
        val tags = node.tags.readOnlyIterator()
        while (tags.hasNext()) appendMatching(byTag[tags.next()], node, origin = null, into)
        appendMatching(byType[node.type], node, origin = null, into)
        appendMatching(universal, node, origin = null, into)
    }

    fun matchingIntoProfiled(node: UiNode, into: MutableList<StyleRule>): Int {
        var checks = 0
        node.id?.let { id -> checks += appendMatching(byId[id], node, origin = null, into) }
        val tags = node.tags.readOnlyIterator()
        while (tags.hasNext()) checks += appendMatching(byTag[tags.next()], node, origin = null, into)
        checks += appendMatching(byType[node.type], node, origin = null, into)
        checks += appendMatching(universal, node, origin = null, into)
        return checks
    }

    private fun appendMatching(
        candidates: List<StyleRule>?,
        node: UiNode,
        origin: StyleOrigin?,
        result: MutableList<StyleRule>,
    ): Int {
        candidates ?: return 0
        for (rule in candidates) {
            if ((origin == null || rule.origin == origin) && rule.matches(node)) result += rule
        }
        return candidates.size
    }
}

data class StyleRule(
    val selector: HssSelector,
    val patch: StylePatch,
    val origin: StyleOrigin,
    val order: Int,
) {
    fun matches(node: UiNode) = selector.matches(node)
}

class StylePatch(private val modifiers: List<Modifier>) {
    fun modifiers(): List<Modifier> = modifiers

    /**
     * The compiled style patch of this rule. Rules are immutable after compilation, so the
     * patch is built once instead of per style-cache miss; consumers must not mutate it.
     */
    internal val compiledPatch: UiStylePatch by lazy { modifiers.toStylePatch() }
}

/**
 * Turns a parsed stylesheet into matchable rules. Every declaration is looked up in
 * [HssSchema], so the compiler itself knows nothing about individual properties.
 */
class HssCompiler(private val origin: StyleOrigin = StyleOrigin.STYLESHEET) {
    fun compile(document: HssDocument): CompiledHss {
        val rules = document.rules.flatMap { rule ->
            val patch = StylePatch(dedupeDeclarations(rule.declarations).mapNotNull(::compileDeclaration))
            // Validate lazy property conversion before publishing a live stylesheet.
            patch.compiledPatch
            rule.selectors.map { selector ->
                val ruleOrigin = if (selector.stateDependent) StyleOrigin.STATE_STYLESHEET else origin
                StyleRule(selector, patch, ruleOrigin, rule.order)
            }
        }
        val keyframes = document.keyframes.associate { keyframes ->
            keyframes.name to UiKeyframes(
                keyframes.name,
                keyframes.frames.flatMap { frame ->
                    frame.offsets.map { offset ->
                        UiKeyframe(
                            offset,
                            compileKeyframeStyle(frame.declarations),
                            compileKeyframeProperties(frame.declarations),
                        )
                    }
                },
            )
        }
        return CompiledHss(rules, keyframes)
    }

    /** Last declaration of a property wins, as in CSS; aliases collapse onto the same slot. */
    private fun dedupeDeclarations(declarations: List<HssDeclaration>): List<HssDeclaration> {
        val byProperty = LinkedHashMap<String, HssDeclaration>()
        for (declaration in declarations) byProperty[declaration.canonicalProperty] = declaration
        return byProperty.values.toList()
    }

    private fun compileKeyframeStyle(declarations: List<HssDeclaration>): UiStylePatch {
        return dedupeDeclarations(declarations).mapNotNull(::compileDeclaration).toStylePatch()
    }

    private fun compileKeyframeProperties(declarations: List<HssDeclaration>): Set<String> {
        return declarations.flatMap { keyframeProperties(it.property, it.value) }.toSet()
    }

    internal fun compileDeclaration(declaration: HssDeclaration): Modifier? =
        HssSchema.compile(declaration.property, declaration.value.trim())
}

/** Canonical name of the declared property, with aliases resolved. */
internal val HssDeclaration.canonicalProperty: String
    get() = HssSchema.find(property)?.name ?: property.lowercase()

internal fun compileHss(source: String, origin: StyleOrigin = StyleOrigin.STYLESHEET): CompiledHss =
    compileHss(parseHss(source), origin)

/** Compiles [document] with its imports read and its variables written in. */
internal fun compileHss(
    document: HssDocument,
    origin: StyleOrigin = StyleOrigin.STYLESHEET,
    scope: HssScope = HssScope.of(document),
): CompiledHss {
    scope.failures.firstOrNull()?.let { (import, reason) -> throw HssParseException(reason, import.start, import.end) }
    return HssCompiler(origin).compile(document.resolved(scope))
}

/** Compiles a single `name: value` pair, as used by HSS-valued node attributes. */
internal fun compileStyleModifier(property: String, value: String): Modifier? =
    HssSchema.compile(property, value.trim())
