package ru.hollowhorizon.hollowengine.client.ui.style

import ru.hollowhorizon.hollowengine.client.ui.UiStyleProperty
import kotlin.reflect.KProperty

/**
 * Single source of truth for one style property.
 *
 * Everything the engine needs to know about a property lives here: its HSS name (and
 * aliases), the invalidation phase it triggers, whether it participates in the layout
 * fingerprint, its default (optionally inherited from the parent style), how two patch
 * values merge and how it interpolates during transitions. Adding a property to the
 * style system is a single declaration in [UiProps].
 *
 * Property application is declarative and order-independent: modifiers write values
 * into a [UiStylePatch] where the last write per property wins (CSS-like), regardless
 * of modifier order semantics such as Compose's measure chains.
 */
class UiStyleProp<T> internal constructor(
    val name: String,
    val aliases: Set<String> = emptySet(),
    val layoutFingerprint: Boolean = false,
    val transitionGroup: String? = null,
    /** Whether the default reads the parent style; drives inheritance-aware cache keys. */
    val inherited: Boolean = false,
    private val defaultValue: UiStyleProp<T>.(parent: UiComputedStyle?) -> T,
    private val mergeValues: ((current: T, next: T) -> T)? = null,
    private val combineValues: ((current: T, next: T) -> T)? = null,
    private val interpolation: ((from: T, to: T, progress: Float) -> T)? = null,
    private val sanitize: ((T) -> T)? = null,
    /** Turns a value written relative to the parent (`font-size: 85%`) into an absolute one. */
    private val resolveRelative: ((value: T, parent: UiComputedStyle?) -> T)? = null,
) {
    internal val index: Int = register(this)

    val transitionable: Boolean get() = interpolation != null

    internal fun defaultFor(parent: UiComputedStyle?): T = defaultValue(this, parent)

    /**
     * Cascade merge (base layer, base→state overlay): structural props deep-merge, scalars
     * are replaced (last-wins). Transforms/tint have no merge here, so base never stacks
     * with a state.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun mergeRaw(current: Any?, next: Any?): Any? {
        val merge = mergeValues ?: return next
        if (current == null) return next
        return merge(current as T, next as T)
    }

    /**
     * State-stacking combine: used only when several simultaneously-active state rules
     * (`:hover` + `:selected` + custom) overlap. Combinable props accumulate (transform
     * add, scale/tint multiply); everything else falls back to cascade merge.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun combineRaw(current: Any?, next: Any?): Any? {
        val combine = combineValues ?: return mergeRaw(current, next)
        if (current == null) return next
        return combine(current as T, next as T)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun interpolateRaw(from: Any?, to: Any?, progress: Float): Any? {
        val interpolate = interpolation ?: return to
        return interpolate(from as T, to as T, progress)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun sanitizeRaw(value: Any?): Any? {
        val sanitize = sanitize ?: return value
        return sanitize(value as T)
    }

    @Suppress("UNCHECKED_CAST")
    internal fun resolveRelativeRaw(value: Any?, parent: UiComputedStyle?): Any? {
        val resolve = resolveRelative ?: return value
        return resolve(value as T, parent)
    }

    operator fun getValue(patch: UiStylePatch, property: KProperty<*>): T? = patch[this]

    operator fun setValue(patch: UiStylePatch, property: KProperty<*>, value: T?) {
        patch[this] = value
    }

    operator fun getValue(style: UiComputedStyle, property: KProperty<*>): T = style[this]

    override fun toString(): String = name

    companion object {
        private val registered = ArrayList<UiStyleProp<*>>()

        private fun register(prop: UiStyleProp<*>): Int {
            registered += prop
            return registered.size - 1
        }

        internal val all: List<UiStyleProp<*>>
            @Suppress("UNUSED_EXPRESSION") get() {
                UiProps // force full property registration before first use
                return registered
            }

        internal val transitionable: List<UiStyleProp<*>> by lazy { all.filter { it.transitionable } }

        internal val layoutFingerprintProps: List<UiStyleProp<*>> by lazy { all.filter { it.layoutFingerprint } }

        internal val inheritedProps: List<UiStyleProp<*>> by lazy { all.filter { it.inherited } }

        private val byTransitionProperty: Map<String, List<UiStyleProp<*>>> by lazy {
            val mapping = HashMap<String, MutableList<UiStyleProp<*>>>()
            for (prop in all) {
                val names = buildSet {
                    add(prop.name)
                    addAll(prop.aliases)
                }
                for (name in names) mapping.getOrPut(name) { mutableListOf() } += prop
                prop.transitionGroup?.let { group -> mapping.getOrPut(group) { mutableListOf() } += prop }
            }
            mapping
        }

        /** Every name a `transitions` declaration may target, including groups and "all". */
        internal val transitionPropertyNames: List<String> by lazy {
            (listOf("all") + byTransitionProperty.keys).distinct().sorted()
        }

        /**
         * Props affected by a `transition: <property>` declaration. Group names
         * ("transform") expand to all members, "all" to every transitionable prop.
         */
        internal fun forTransitionProperty(property: String): List<UiStyleProp<*>> {
            if (property == "all") return transitionable
            return byTransitionProperty[property].orEmpty()
        }
    }
}

/**
 * Sparse, mutable set of style property values written by modifiers, HSS declarations
 * and engine defaults.
 */
class UiStylePatch {
    internal val values = LinkedHashMap<UiStyleProp<*>, Any?>()

    var explicitProperties: Set<UiStyleProperty>? = null

    fun isEmpty(): Boolean = values.isEmpty() && explicitProperties.isNullOrEmpty()

    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(prop: UiStyleProp<T>): T? = values[prop] as T?

    /** Sets the property (last-wins within a single patch/source). */
    operator fun <T> set(prop: UiStyleProp<T>, value: T?) {
        if (value == null) values.remove(prop) else values[prop] = value
    }

    /** Cascade overlay of [other] onto this patch. */
    fun merge(other: UiStylePatch) {
        for ((prop, value) in other.values) {
            values[prop] = prop.mergeRaw(values[prop], value)
        }
        other.explicitProperties?.let { explicitProperties = explicitProperties.orEmpty() + it }
    }

    /** Stacks [other] onto this patch using combine semantics. */
    fun combineWith(other: UiStylePatch) {
        for ((prop, value) in other.values) {
            values[prop] = prop.combineRaw(values[prop], value)
        }
        other.explicitProperties?.let { explicitProperties = explicitProperties.orEmpty() + it }
    }

    /** Materializes the final style, inheriting unset inheritable values from [parent]. */
    fun resolve(parent: UiComputedStyle? = null): UiComputedStyle {
        val props = UiStyleProp.all
        val resolved = arrayOfNulls<Any?>(props.size)
        for (prop in props) {
            val value = if (values.containsKey(prop)) values[prop] else prop.defaultFor(parent)
            resolved[prop.index] = prop.sanitizeRaw(prop.resolveRelativeRaw(value, parent))
        }
        return UiComputedStyle(resolved, explicitProperties ?: emptySet())
    }

    override fun equals(other: Any?): Boolean {
        return other is UiStylePatch && values == other.values && explicitProperties.orEmpty() == other.explicitProperties.orEmpty()
    }

    override fun hashCode(): Int = 31 * values.hashCode() + explicitProperties.orEmpty().hashCode()
}

/**
 * Fully resolved, immutable style of a node: every property has a value. Replaces the
 * former field-per-property snapshot; property access goes through [UiStyleProp] keys
 * with typed accessors declared next to each property in [UiProps].
 */
class UiComputedStyle internal constructor(
    private val values: Array<Any?>,
    val explicitProperties: Set<UiStyleProperty>,
) {
    private var hash: Int = 0
    private var hashComputed = false
    private var layoutHash: Int = 0
    private var layoutHashComputed = false

    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(prop: UiStyleProp<T>): T = values[prop.index] as T

    /**
     * Applies a keyframe/override patch on top of this style, replacing each set property
     * with the patch's absolute value. Keyframes express absolute targets, so this does
     * NOT use the cascade's combine semantics (that stacking already happened when the
     * base style was resolved).
     */
    fun with(patch: UiStylePatch): UiComputedStyle {
        if (patch.values.isEmpty()) return this
        val next = values.copyOf()
        for ((prop, value) in patch.values) {
            next[prop.index] = prop.sanitizeRaw(value)
        }
        return UiComputedStyle(next, explicitProperties + patch.explicitProperties.orEmpty())
    }

    /**
     * Interpolates towards [to]: non-transitionable properties take the target value,
     * transitionable ones blend by their per-property progress.
     */
    fun interpolate(to: UiComputedStyle, progress: UiTransitionProgress): UiComputedStyle {
        if (progress.complete()) return to
        val next = to.values.copyOf()
        for (prop in UiStyleProp.transitionable) {
            val local = progress[prop]
            // Progress may overshoot 1.0 (e.g. back/overshooting cubic-bezier easings),
            // so only an exact 1.0 is treated as settled.
            if (local == 1f) continue
            val blended = prop.interpolateRaw(values[prop.index], to.values[prop.index], local)
            next[prop.index] = prop.sanitizeRaw(blended)
        }
        return UiComputedStyle(next, to.explicitProperties)
    }

    /** Whether a `transition: <property>` declaration observes a change towards [target]. */
    fun changed(property: String, target: UiComputedStyle): Boolean {
        if (target === this) return false
        return UiStyleProp.forTransitionProperty(property).any { prop ->
            values[prop.index] != target.values[prop.index]
        }
    }

    internal fun inheritedValuesEqual(other: UiComputedStyle): Boolean {
        if (this === other) return true
        val props = UiStyleProp.inheritedProps
        for (index in props.indices) {
            val slot = props[index].index
            if (values[slot] != other.values[slot]) return false
        }
        return true
    }

    /** Hash over layout-affecting properties; used to gate layout invalidation. */
    fun layoutFingerprint(): Int {
        if (!layoutHashComputed) {
            var result = 1
            val props = UiStyleProp.layoutFingerprintProps
            for (index in props.indices) {
                val prop = props[index]
                result = 31 * result + (values[prop.index]?.hashCode() ?: 0)
            }
            layoutHash = result
            layoutHashComputed = true
        }
        return layoutHash
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is UiComputedStyle && values.contentEquals(other.values) && explicitProperties == other.explicitProperties
    }

    override fun hashCode(): Int {
        if (!hashComputed) {
            hash = 31 * values.contentHashCode() + explicitProperties.hashCode()
            hashComputed = true
        }
        return hash
    }
}

/** Per-property transition progress; unlisted properties are considered complete. */
class UiTransitionProgress internal constructor(
    private val values: Map<UiStyleProp<*>, Float>,
) {
    operator fun get(prop: UiStyleProp<*>): Float = values[prop] ?: 1f

    fun complete(): Boolean = values.values.all { it == 1f }

    companion object {
        val Complete = UiTransitionProgress(emptyMap())

        fun all(progress: Float): UiTransitionProgress {
            if (progress == 1f) return Complete
            return UiTransitionProgress(UiStyleProp.transitionable.associateWith { progress })
        }

        internal fun of(values: Map<UiStyleProp<*>, Float>): UiTransitionProgress =
            if (values.isEmpty()) Complete else UiTransitionProgress(values)
    }
}
