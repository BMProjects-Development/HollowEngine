package ru.hollowhorizon.hollowengine.client.ui.style

import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollbarThumbType
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollbarType
import java.util.*

class UiModifierResolver(
    private val theme: CompiledHss? = null,
    private val stylesheet: CompiledHss? = null,
    private val transitions: UiTransitionState = UiTransitionState(),
    private val animations: UiAnimationState = UiAnimationState(),
) {
    private val resolveStack = StyleResolveStack()
    private val stylesheetStack = ArrayDeque<UiNode>()
    private val stylesheetReferences = ArrayList<UiStylesheetReference>()
    private val matchedRulesScratch = ArrayList<StyleRule>()
    private var stylesheetRoot: UiNode? = null
    private var stylesheetTreeDrawRevision = Long.MIN_VALUE
    private var treeCache: TreeCacheEntry? = null
    private var nextScopeId = 1L
    private val rootScope = StyleScope(
        stylesheets = emptyList(),
        keyframes = buildMap {
            theme?.keyframes?.let(::putAll)
            stylesheet?.keyframes?.let(::putAll)
        },
        id = 0L,
    )

    fun resolve(
        root: UiNode,
        nowMillis: Long = 0L,
        animate: Boolean = true,
        profile: UiProfileFrame? = null,
    ): List<UiNode> {
        if (profile != null) profile.stylePasses++
        val stylesheetRevision = stylesheetRevision(root)
        val treeKey = TreeCacheKey(
            root = root,
            subtreeLayoutRevision = root.layoutState.subtreeLayoutRevision,
            subtreeDrawRevision = root.layoutState.subtreeDrawRevision,
            subtreeInputRevision = root.layoutState.subtreeInputRevision,
            stylesheetRevision = stylesheetRevision,
            animate = animate,
        )
        val previousTreeCache = treeCache
        previousTreeCache?.takeIf { it.key == treeKey && !it.requiresRefresh }?.let {
            if (profile != null) {
                profile.styleVisitedNodes += it.nodes.size
                profile.styleCacheHits += it.nodes.size
            }
            return it.nodes
        }
        val stylesheetChanged = previousTreeCache != null &&
                previousTreeCache.key.stylesheetRevision != stylesheetRevision
        transitions.beginResolve()
        val nodes = ArrayList<UiNode>(treeCache?.nodes?.size ?: 64)
        val requiresRefresh = resolveNodes(root, nowMillis, animate, nodes, profile, stylesheetChanged)
        return nodes.also {
            treeCache = TreeCacheEntry(
                key = treeKey.copy(
                    subtreeLayoutRevision = root.layoutState.subtreeLayoutRevision,
                    subtreeDrawRevision = root.layoutState.subtreeDrawRevision,
                    subtreeInputRevision = root.layoutState.subtreeInputRevision,
                    stylesheetRevision = stylesheetRevision,
                ),
                nodes = nodes,
                requiresRefresh = animate && (transitions.activeDuringResolve || requiresRefresh),
            )
        }
    }

    private fun resolveNodes(
        root: UiNode,
        nowMillis: Long,
        animate: Boolean,
        nodes: MutableList<UiNode>,
        profile: UiProfileFrame?,
        stylesheetChanged: Boolean,
    ): Boolean {
        var requiresRefresh = false
        val stack = resolveStack
        stack.clear()
        stack.push(root, parent = null, scope = rootScope, ancestorRevision = 0L)
        while (stack.isNotEmpty()) {
            stack.pop()
            val node = stack.currentNode
            if (profile != null) profile.styleVisitedNodes++
            val parent = stack.currentParent
            val inheritedScope = stack.currentScope
            val ancestorRevision = stack.currentAncestorRevision
            val layoutState = node.layoutState
            val state = stateFor(node)
            val modifiers = flattenedModifiers(state, node, layoutState.drawRevision)
            val scopedScope = scopedStyleScope(state, inheritedScope, modifiers)
            val resolved = resolveBaseStyle(
                state,
                node,
                parent,
                scopedScope,
                modifiers,
                ancestorRevision,
                layoutState.drawRevision,
                profile,
                stylesheetChanged,
            )
            val transitioned =
                if (animate) transitions.apply(state.motion, resolved.snapshot, nowMillis) else resolved.snapshot
            val finalStyle = if (animate) {
                animations.apply(state.motion, transitioned, scopedScope.keyframes, nowMillis)
            } else {
                transitioned
            }
            nodes += node
            node.resolvedModifiers = resolved.modifiers.flat
            node.resolvedSnapshot = finalStyle
            // Fingerprint the transitioned + animated style, not the base: an animation on a
            // layout prop (size/padding/transform) must bump the layout revision so the frame rebuilds
            // it, while a draw-only animation (colour/opacity) leaves the fingerprint and the layout untouched.
            node.layoutState.updateResolvedLayoutFingerprint(finalStyle.layoutFingerprint())
            val modifierRefresh = finalStyle.requiresModifierRefresh()
            if (!requiresRefresh && modifierRefresh) requiresRefresh = true
            if (profile != null && modifierRefresh) profile.animatedNodes++
            val descendantRevision = maxOf(
                ancestorRevision,
                layoutState.drawRevision,
            )
            for (index in node.children.indices.reversed()) {
                stack.push(node.children[index], finalStyle, scopedScope, descendantRevision)
            }
        }
        return requiresRefresh
    }

    private fun stateFor(node: UiNode): NodeResolveState {
        val existing = node.layoutState.styleResolverState as? NodeResolveState
        if (existing != null && existing.owner === this) return existing
        return NodeResolveState(this).also { node.layoutState.styleResolverState = it }
    }

    private fun flattenedModifiers(
        state: NodeResolveState,
        node: UiNode,
        drawRevision: Long,
    ): List<Modifier> {
        // Modifier/tags/state/attribute collections invalidate drawRevision. layoutRevision also
        // changes for every frame of a layout animation, so using it here would defeat this cache.
        state.modifierEntry?.takeIf { it.drawRevision == drawRevision }?.let { return it.modifiers }
        return node.modifiers.flattenModifiers().also { modifiers ->
            state.modifierEntry = ModifierCacheEntry(drawRevision, modifiers)
        }
    }

    private fun stylesheetRevision(root: UiNode): Long {
        val drawRevision = root.layoutState.subtreeDrawRevision
        // Imports only need rediscovery after a source-style change; resource revisions themselves
        // are still sampled every frame so a resource reload invalidates the tree immediately.
        if (stylesheetRoot !== root || stylesheetTreeDrawRevision != drawRevision) {
            stylesheetRoot = root
            stylesheetTreeDrawRevision = drawRevision
            stylesheetReferences.clear()
            stylesheetStack.clear()
            stylesheetStack.add(root)
            while (stylesheetStack.isNotEmpty()) {
                val node = stylesheetStack.removeLast()
                for (modifier in flattenedModifiers(stateFor(node), node, node.layoutState.drawRevision)) {
                    if (modifier is StyleImportModifier) stylesheetReferences += modifier.reference
                }
                for (child in node.children) stylesheetStack.add(child)
            }
        }

        var revision = 1L
        for (reference in stylesheetReferences) revision = revision * 31L + reference.revision()
        return revision
    }

    /**
     * Splits the styling that applies to [node] into two layers:
     *  - the base layer (theme defaults, non-state stylesheet rules, node modifiers,
     *    attribute modifiers) that cascades last-wins;
     *  - the currently-active state rules (`:hover`, `:selected`, custom), kept as separate
     *    patches so the resolver can stack their effects with each other before overlaying
     *    them onto the base.
     * [flat] preserves the original order for node.resolvedModifiers (event/draw dispatch).
     */
    private fun resolveModifiers(
        node: UiNode,
        scope: StyleScope,
        nodeModifiers: List<Modifier>,
        profile: UiProfileFrame?,
    ): ResolvedModifiers {
        val baseRuleModifiers = ArrayList<Modifier>()
        theme?.let {
            val themeRules = ArrayList<RankedRule>()
            collectMatchedRules(it, 0, node, StyleOrigin.THEME_DEFAULTS, themeRules, stateSink = null, profile)
            appendInCascadeOrder(themeRules, baseRuleModifiers)
        }
        val baseRules = ArrayList<RankedRule>()
        val stateRules = ArrayList<RankedRule>()
        var sheetRank = 0
        stylesheet?.let {
            collectMatchedRules(it, sheetRank++, node, StyleOrigin.STYLESHEET, baseRules, stateRules, profile)
        }
        for (scoped in scope.stylesheets) {
            collectMatchedRules(scoped, sheetRank++, node, StyleOrigin.STYLESHEET, baseRules, stateRules, profile)
        }
        appendInCascadeOrder(baseRules, baseRuleModifiers)

        if (stateRules.size > 1) stateRules.sortWith(RankedRuleComparator)
        val orderedStateRules = stateRules.map { it.rule }

        val attributeModifiers = attributeModifiers(node)
        val baseModifiers =
            ArrayList<Modifier>(baseRuleModifiers.size + nodeModifiers.size + attributeModifiers.size)
        baseModifiers += baseRuleModifiers
        baseModifiers += nodeModifiers
        baseModifiers += attributeModifiers

        // Flat list keeps the historical dispatch order (base rules, then state rules, then
        // node/attribute modifiers).
        val flat =
            ArrayList<Modifier>(baseRuleModifiers.size + orderedStateRules.size + nodeModifiers.size + attributeModifiers.size)
        flat += baseRuleModifiers
        orderedStateRules.forEach { flat += it.patch.modifiers() }
        flat += nodeModifiers
        flat += attributeModifiers

        return ResolvedModifiers(
            flat = flat,
            baseModifiers = baseModifiers,
            stateRulePatches = orderedStateRules.map { it.patch.compiledPatch },
        )
    }

    private fun appendInCascadeOrder(rules: ArrayList<RankedRule>, sink: MutableList<Modifier>) {
        if (rules.size > 1) rules.sortWith(RankedRuleComparator)
        for (ranked in rules) sink += ranked.rule.patch.modifiers()
    }

    private fun collectMatchedRules(
        hss: CompiledHss,
        sheetRank: Int,
        node: UiNode,
        baseOrigin: StyleOrigin,
        baseSink: MutableList<RankedRule>,
        stateSink: MutableList<RankedRule>?,
        profile: UiProfileFrame?,
    ) {
        val matched = matchedRulesScratch
        matched.clear()
        val checks = if (profile == null) {
            hss.matchingInto(node, matched)
            0
        } else {
            hss.matchingIntoProfiled(node, matched)
        }
        if (profile != null) {
            profile.selectorChecks += checks
            profile.matchedRules += matched.size
        }
        if (matched.isEmpty()) return
        for (rule in matched) {
            when (rule.origin) {
                baseOrigin -> baseSink += RankedRule(rule, sheetRank)
                StyleOrigin.STATE_STYLESHEET -> stateSink?.add(RankedRule(rule, sheetRank))
                else -> Unit
            }
        }
        matched.clear()
    }

    private fun scopedStyleScope(
        state: NodeResolveState,
        inheritedScope: StyleScope,
        modifiers: List<Modifier>,
    ): StyleScope {
        var imports: ArrayList<StyleImportModifier>? = null
        for (modifier in modifiers) {
            if (modifier !is StyleImportModifier) continue
            if (imports == null) imports = ArrayList()
            imports += modifier
        }
        val resolvedImports = imports ?: return inheritedScope
        state.scopeEntry?.takeIf { it.matches(inheritedScope.id, resolvedImports) }?.let { return it.scope }
        val snapshots = resolvedImports.map { StyleImportSnapshot(it.reference, it.reference.revision()) }
        val key = ScopeCacheKey(inheritedScope.id, snapshots)
        val stylesheets = ArrayList<CompiledHss>(inheritedScope.stylesheets.size + resolvedImports.size)
        stylesheets += inheritedScope.stylesheets
        val keyframes = LinkedHashMap(inheritedScope.keyframes)
        resolvedImports.forEach { modifier ->
            val stylesheet = modifier.reference.resolve()
            stylesheets += stylesheet
            keyframes.putAll(stylesheet.keyframes)
        }
        return StyleScope(stylesheets, keyframes, nextScopeId++).also { scope ->
            state.scopeEntry = ScopeCacheEntry(key, scope)
        }
    }

    private fun resolveBaseStyle(
        state: NodeResolveState,
        node: UiNode,
        parent: UiComputedStyle?,
        scope: StyleScope,
        modifiers: List<Modifier>,
        ancestorRevision: Long,
        drawRevision: Long,
        profile: UiProfileFrame?,
        stylesheetChanged: Boolean,
    ): ResolvedNodeStyle {
        val cached = state.styleEntry
        cached?.takeIf {
            it.key.matches(
                scope.id,
                parent,
                ancestorRevision,
                drawRevision,
            )
        }?.let {
            if (profile != null) profile.styleCacheHits++
            return it.resolved
        }
        if (profile != null) {
            profile.styleRecomputedNodes++
            when {
                stylesheetChanged -> profile.styleMissStylesheet++
                cached == null -> profile.styleMissUncached++
                cached.key.scopeId != scope.id -> profile.styleMissScope++
                cached.key.drawRevision != drawRevision -> profile.styleMissNodeRevision++
                cached.key.ancestorRevision != ancestorRevision -> profile.styleMissAncestor++
                else -> profile.styleMissParent++
            }
        }
        val key = StyleCacheKey(
            scopeId = scope.id,
            parent = parent,
            ancestorRevision = ancestorRevision,
            drawRevision = drawRevision,
        )
        val resolved = resolveModifiers(node, scope, modifiers, profile)
        val mutable = engineDefaults(node)
        mutable.merge(resolved.baseModifiers.toStylePatch())
        if (resolved.stateRulePatches.isNotEmpty()) {
            // Active states stack with each other, then overlay the base (base never stacks).
            val combinedStates = UiStylePatch()
            resolved.stateRulePatches.forEach { combinedStates.combineWith(it) }
            mutable.merge(combinedStates)
        }
        return ResolvedNodeStyle(resolved, mutable.resolve(parent)).also { style ->
            state.styleEntry = StyleCacheEntry(key, style)
        }
    }

    private fun attributeModifiers(node: UiNode): List<Modifier> {
        if (node.attributes.isEmpty()) return emptyList()
        return node.attributes.mapNotNull { (name, value) -> compileStyleModifier(name, value) }
    }

    private fun engineDefaults(node: UiNode): UiStylePatch {
        val style = UiStylePatch()
        style.transitions = DefaultTransformTransitions
        when (node.type) {
            UiTextType -> {
                style.foreground = UiColor.White
                style.size = UiSize(UiLength.Auto, UiLength.Auto)
                style.minSize = UiSize(0.px, 0.px)
            }

            UiImageType,
            UiItemType,
            UiEntityType,
                -> {
                style.size = UiSize(16.px, 16.px)
            }

            UiScrollbarType -> {
                style.background = UiPaint.Color(UiColor(0f, 0f, 0f, 0.42f))
                style.borderRadius = 3.5f
            }

            UiScrollbarThumbType -> {
                style.background = UiPaint.Color(UiColor(0.78f, 0.84f, 0.94f, 0.9f))
                style.borderRadius = 3.5f
                style.clickable = true
                style.draggable = true
                style.hoverable = true
            }
        }
        return style
    }

    companion object {
        private val DefaultTransformTransitions = listOf(
            UiTransition("translate", 200L, TransitionEasing.EASE_OUT),
            UiTransition("rotate", 200L, TransitionEasing.EASE_OUT),
            UiTransition("scale", 200L, TransitionEasing.EASE_OUT),
            UiTransition("perspective", 200L, TransitionEasing.EASE_OUT),
        )

        private val RankedRuleComparator =
            compareBy<RankedRule> { it.rule.selector.specificity }.thenBy { it.sheetRank }.thenBy { it.rule.order }
    }
}

private class RankedRule(val rule: StyleRule, val sheetRank: Int)

private class NodeResolveState(val owner: UiModifierResolver) {
    var modifierEntry: ModifierCacheEntry? = null
    var scopeEntry: ScopeCacheEntry? = null
    var styleEntry: StyleCacheEntry? = null
    val motion = UiNodeMotionState()
}

private class ResolvedModifiers(
    val flat: List<Modifier>,
    val baseModifiers: List<Modifier>,
    val stateRulePatches: List<UiStylePatch>,
)

private class ResolvedNodeStyle(
    val modifiers: ResolvedModifiers,
    val snapshot: UiComputedStyle,
)

private data class StyleScope(
    val stylesheets: List<CompiledHss>,
    val keyframes: Map<String, UiKeyframes>,
    val id: Long,
)

private data class StyleImportSnapshot(
    val reference: UiStylesheetReference,
    val revision: Long,
)

private data class ScopeCacheKey(
    val inheritedScopeId: Long,
    val imports: List<StyleImportSnapshot>,
)

private data class ScopeCacheEntry(
    val key: ScopeCacheKey,
    val scope: StyleScope,
) {
    fun matches(inheritedScopeId: Long, imports: List<StyleImportModifier>): Boolean {
        if (key.inheritedScopeId != inheritedScopeId || key.imports.size != imports.size) return false
        for (index in imports.indices) {
            val snapshot = key.imports[index]
            val reference = imports[index].reference
            if (snapshot.reference != reference || snapshot.revision != reference.revision()) return false
        }
        return true
    }
}

private class StyleCacheKey(
    val scopeId: Long,
    val parent: UiComputedStyle?,
    val ancestorRevision: Long,
    val drawRevision: Long,
) {
    fun matches(
        scopeId: Long,
        parent: UiComputedStyle?,
        ancestorRevision: Long,
        drawRevision: Long,
    ): Boolean {
        if (this.scopeId != scopeId ||
            this.ancestorRevision != ancestorRevision ||
            this.drawRevision != drawRevision
        ) {
            return false
        }
        val cachedParent = this.parent
        if (cachedParent === parent) return true
        if (cachedParent == null || parent == null) return false
        return cachedParent.inheritedValuesEqual(parent)
    }
}

private data class StyleCacheEntry(
    val key: StyleCacheKey,
    val resolved: ResolvedNodeStyle,
)

private class ModifierCacheEntry(
    val drawRevision: Long,
    val modifiers: List<Modifier>,
)

private class StyleResolveStack(initialCapacity: Int = 64) {
    private var nodes = arrayOfNulls<UiNode>(initialCapacity)
    private var parents = arrayOfNulls<UiComputedStyle>(initialCapacity)
    private var scopes = arrayOfNulls<StyleScope>(initialCapacity)
    private var ancestorRevisions = LongArray(initialCapacity)
    private var size = 0

    lateinit var currentNode: UiNode
        private set
    var currentParent: UiComputedStyle? = null
        private set
    lateinit var currentScope: StyleScope
        private set
    var currentAncestorRevision: Long = 0L
        private set

    fun isNotEmpty(): Boolean = size > 0

    fun clear() {
        for (index in 0 until size) {
            nodes[index] = null
            parents[index] = null
            scopes[index] = null
        }
        size = 0
    }

    fun push(node: UiNode, parent: UiComputedStyle?, scope: StyleScope, ancestorRevision: Long) {
        ensureCapacity(size + 1)
        nodes[size] = node
        parents[size] = parent
        scopes[size] = scope
        ancestorRevisions[size] = ancestorRevision
        size++
    }

    fun pop() {
        val index = --size
        currentNode = nodes[index]!!
        currentParent = parents[index]
        currentScope = scopes[index]!!
        currentAncestorRevision = ancestorRevisions[index]
        nodes[index] = null
        parents[index] = null
        scopes[index] = null
    }

    private fun ensureCapacity(required: Int) {
        if (required <= nodes.size) return
        val next = maxOf(required, nodes.size * 2)
        nodes = nodes.copyOf(next)
        parents = parents.copyOf(next)
        scopes = scopes.copyOf(next)
        ancestorRevisions = ancestorRevisions.copyOf(next)
    }
}

private data class TreeCacheKey(
    val root: UiNode,
    val subtreeLayoutRevision: Long,
    val subtreeDrawRevision: Long,
    val subtreeInputRevision: Long,
    val stylesheetRevision: Long,
    val animate: Boolean,
)

private data class TreeCacheEntry(
    val key: TreeCacheKey,
    val nodes: List<UiNode>,
    val requiresRefresh: Boolean,
)

private fun UiComputedStyle.requiresModifierRefresh(): Boolean {
    val animations = animations
    if (animations.isEmpty()) return false
    return animations.any { animation -> animation.totalDurationMillis()?.let { it > 0L } ?: true }
}
