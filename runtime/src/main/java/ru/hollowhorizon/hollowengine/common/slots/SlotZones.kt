package ru.hollowhorizon.hollowengine.common.slots

import net.minecraft.world.item.ItemStack

/**
 * Rules for one slot. Every field is optional: what is left unset keeps the zone's answer.
 *
 * Nothing is read back from the zone here, and the block is not evaluated until the zone is fully
 * configured, so `slot(0) { ... }` before or after `stackLimit = 1` gives the same result.
 */
class SlotRulesBuilder internal constructor() {
    var canInsert: SlotFilter? = null
    var canExtract: SlotFilter? = null
    var stackLimit: Int? = null
}

/**
 * Declares one zone: the storage behind it, what it accepts, and who is told when it changes.
 *
 * Two kinds of hook, and the difference matters:
 *
 * [allowInsert] and [allowExtract] decide whether a click may happen. They run before anything is
 * committed, several of them may run for one gesture, and any of them saying no throws the whole gesture
 * away, so they have to be pure tests. Granting a reward from one would leave that reward behind when a
 * later check refuses the click, and the player could repeat it.
 *
 * [afterInsert], [afterExtract] and [onChange] run once the click is committed and cannot be undone. That
 * is where effects belong.
 *
 * A veto cannot be evaluated on the client, so declaring [allowInsert] or [allowExtract] costs the screen
 * its prediction; the after-hooks only observe and cost nothing.
 */
class SlotZoneBuilder internal constructor(val name: String, val source: SlotSource) {
    var role: SlotZoneRole = SlotZoneRole.BOTH
    var copyOnClick: Boolean = false
    var canInsert: SlotFilter = SlotFilter.Any
    var canExtract: SlotFilter = SlotFilter.Any

    /**
     * A cap applied on top of whatever each slot already allows.
     *
     * Unset it reads the most a slot of this source can hold, which keeps a uniform zone (four armor slots,
     * say) down to one published rule. Setting it always tightens: it is combined with the source's own
     * per-slot limit by taking the smaller, never by replacing it.
     */
    var stackLimit: Int
        get() = explicitStackLimit ?: sourceStackLimit
        set(value) {
            explicitStackLimit = value
        }

    private var explicitStackLimit: Int? = null
    private val sourceStackLimit: Int by lazy {
        (0 until source.size).maxOfOrNull(source::stackLimit) ?: DEFAULT_STACK_LIMIT
    }

    private val overrideBlocks = mutableMapOf<Int, SlotRulesBuilder.() -> Unit>()
    internal var allowInsertCheck: ((Int, ItemStack) -> Boolean)? = null
        private set
    internal var allowExtractCheck: ((Int, ItemStack) -> Boolean)? = null
        private set
    internal var afterInsertHandler: ((Int, ItemStack) -> Unit)? = null
        private set
    internal var afterExtractHandler: ((Int, ItemStack) -> Unit)? = null
        private set
    internal var changeHandler: ((Int, ItemStack) -> Unit)? = null
        private set

    /**
     * Overrides the zone's rules for a single slot, e.g. one armor slot among four.
     *
     * The block is kept and evaluated after the zone is fully configured, so it never captures a half-built
     * zone. It can only narrow: the resulting limit is the smallest of the zone's, the override's and the
     * storage's own.
     */
    fun slot(index: Int, block: SlotRulesBuilder.() -> Unit) {
        require(index in 0 until source.size) { "Slot $index is outside zone '$name' of size ${source.size}" }
        overrideBlocks[index] = block
    }

    /** Pure test run before a click commits: return false to refuse items landing in [slot]. */
    fun allowInsert(check: (slot: Int, stack: ItemStack) -> Boolean) {
        allowInsertCheck = check
    }

    /** Pure test run before a click commits: return false to refuse items leaving [slot]. */
    fun allowExtract(check: (slot: Int, stack: ItemStack) -> Boolean) {
        allowExtractCheck = check
    }

    /** Runs after items have landed in a slot of this zone. The place for effects. */
    fun afterInsert(handler: (slot: Int, stack: ItemStack) -> Unit) {
        afterInsertHandler = handler
    }

    /** Runs after items have left a slot of this zone. The place for effects. */
    fun afterExtract(handler: (slot: Int, stack: ItemStack) -> Unit) {
        afterExtractHandler = handler
    }

    /** Runs after a slot of this zone settled on new contents, whichever direction it moved. */
    fun onChange(handler: (slot: Int, stack: ItemStack) -> Unit) {
        changeHandler = handler
    }

    private fun defaults() = SlotRules(canInsert, canExtract, stackLimit)

    /**
     * Resolves every slot's rules from the three sources that have a say: the zone, an explicit [slot]
     * override, and the storage itself.
     *
     * Filters replace as they narrow, an override standing in for the zone's answer where it gave one, but
     * the storage's own filter always applies on top of whatever came out. Limits only ever tighten: the
     * result is the smallest of the three, so neither an override nor a storage can hand back capacity that
     * the zone refused.
     */
    private fun resolvedOverrides(): List<SlotRuleOverride> {
        val zone = defaults()
        return (0 until source.size).mapNotNull { index ->
            val explicit = overrideBlocks[index]?.let { SlotRulesBuilder().apply(it) }
            val rules = SlotRules(
                canInsert = (explicit?.canInsert ?: zone.canInsert) and source.slotFilter(index),
                canExtract = explicit?.canExtract ?: zone.canExtract,
                stackLimit = minOf(
                    zone.stackLimit,
                    explicit?.stackLimit ?: Int.MAX_VALUE,
                    source.stackLimit(index),
                ),
            )
            SlotRuleOverride(index, rules).takeIf { rules.differsFrom(zone) }
        }
    }

    internal fun buildLayout(offset: Int): SlotZoneLayout {
        val resolved = resolvedOverrides()
        return SlotZoneLayout(
            name = name,
            offset = offset,
            size = source.size,
            role = role,
            rules = defaults(),
            overrides = resolved,
            copyOnClick = copyOnClick,
            predictable = allowInsertCheck == null && allowExtractCheck == null &&
                    resolved.all { it.rules.isPredictable },
        )
    }

    internal fun buildBinding() = SlotZoneBinding(
        name = name,
        source = source,
        allowInsert = allowInsertCheck,
        allowExtract = allowExtractCheck,
        afterInsert = afterInsertHandler,
        afterExtract = afterExtractHandler,
        onChange = changeHandler,
    )
}

/** A zone's server-side half: the storage it reads and writes, and the hooks watching it. */
class SlotZoneBinding internal constructor(
    val name: String,
    val source: SlotSource,
    internal val allowInsert: ((Int, ItemStack) -> Boolean)?,
    internal val allowExtract: ((Int, ItemStack) -> Boolean)?,
    internal val afterInsert: ((Int, ItemStack) -> Unit)?,
    internal val afterExtract: ((Int, ItemStack) -> Unit)?,
    internal val onChange: ((Int, ItemStack) -> Unit)?,
)

/**
 * Declares the slot structure of one UI.
 *
 * Zone order is the quick-move ring: shift-clicking walks from a slot's own zone to the next declared
 * one and wraps around, skipping whatever will not take the item. [quickMove] bends that for a single
 * zone without disturbing the rest of the ring.
 */
class SlotZonesBuilder internal constructor() {
    private val builders = mutableListOf<SlotZoneBuilder>()
    private val routes = mutableMapOf<String, String>()
    internal var validity: (() -> Boolean)? = null
        private set

    fun zone(name: String, source: SlotSource, block: SlotZoneBuilder.() -> Unit = {}) {
        require(builders.none { it.name == name }) { "Zone '$name' is already declared" }
        require(source.size > 0) { "Zone '$name' has no slots" }
        requireDistinctStorage(name, source)
        builders += SlotZoneBuilder(name, source).apply(block)
    }

    /**
     * Forces the first quick-move target of a zone, e.g. `quickMove("armor" to "player")`.
     *
     * A zone cannot route to itself: quick-move would merge the stack into its own zone and then overwrite
     * the source slot with what was left over, destroying the items it just moved.
     */
    fun quickMove(vararg targets: Pair<String, String>) {
        targets.forEach { (from, to) ->
            require(from != to) { "Quick-move route '$from' points at itself" }
            routes[from] = to
        }
    }

    /**
     * Refuses any slot that is covered twice, whether by an earlier zone or by this source itself.
     *
     * Every view of a slot keeps its own snapshot of it, so two of them let one gesture write two different
     * results to the same storage and lose whatever the second write overwrote. Sources report the physical
     * slot behind each index ([SlotSource.storageSlotKey]) precisely so this can be caught at declaration
     * rather than in a dupe report.
     *
     * The source is checked against itself too: nothing stops a caller writing
     * `EquipmentSource(entity, listOf(MAINHAND, MAINHAND))`.
     */
    private fun requireDistinctStorage(name: String, source: SlotSource) {
        val own = HashMap<Any, Int>(source.size)
        for (index in 0 until source.size) {
            val key = source.storageSlotKey(index)
            own.put(key, index)?.let { first ->
                error("Zone '$name' covers the same storage slot twice, at index $first and $index")
            }
            val clash = builders.firstOrNull { existing ->
                (0 until existing.source.size).any { existing.source.storageSlotKey(it) == key }
            } ?: continue
            error("Zone '$name' slot $index is already covered by zone '${clash.name}'")
        }
    }

    /**
     * Keeps the UI open only while [predicate] holds, checked every server tick. Nothing is implied by
     * default: a screen stays open until the player or the script closes it, so a quest stash can be
     * reachable from anywhere unless a script says otherwise.
     */
    fun validWhile(predicate: () -> Boolean) {
        validity = predicate
    }

    internal fun buildLayout(): SlotLayout {
        var offset = 0
        val zones = builders.map { builder ->
            builder.buildLayout(offset).also { offset += it.size }
        }
        routes.forEach { (from, to) ->
            require(builders.any { it.name == from }) { "Quick-move route source '$from' is not a declared zone" }
            require(builders.any { it.name == to }) { "Quick-move route target '$to' is not a declared zone" }
            require(from != to) { "Quick-move route '$from' points at itself" }
        }
        return SlotLayout(zones, builders.map { it.name }, routes.toMap())
    }

    internal fun buildBindings(): Map<String, SlotZoneBinding> =
        builders.associate { it.name to it.buildBinding() }
}
