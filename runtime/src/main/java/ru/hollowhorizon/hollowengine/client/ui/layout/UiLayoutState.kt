package ru.hollowhorizon.hollowengine.client.ui.layout

import ru.hollowhorizon.hollowengine.client.ui.UiNode
import java.util.*

internal class InvalidatingMutableList<E>(
    values: Iterable<E> = emptyList(),
    private val onChange: () -> Unit,
) : AbstractMutableList<E>() {
    private val items = values.toMutableList()

    override val size: Int
        get() = items.size

    override fun get(index: Int): E = items[index]

    override fun clear() {
        if (items.isEmpty()) return
        items.clear()
        onChange()
    }

    override fun add(index: Int, element: E) {
        items.add(index, element)
        onChange()
    }

    override fun removeAt(index: Int): E {
        return items.removeAt(index).also { onChange() }
    }

    override fun set(index: Int, element: E): E {
        val previous = items.set(index, element)
        if (previous != element) onChange()
        return previous
    }
}

internal class InvalidatingMutableSet<E>(
    values: Iterable<E> = emptyList(),
    private val onChange: () -> Unit,
) : AbstractMutableSet<E>() {
    private val items = values.toMutableSet()

    override val size: Int
        get() = items.size

    override fun contains(element: E): Boolean = items.contains(element)

    override fun add(element: E): Boolean {
        return items.add(element).also { changed -> if (changed) onChange() }
    }

    override fun clear() {
        if (items.isEmpty()) return
        items.clear()
        onChange()
    }

    override fun iterator(): MutableIterator<E> {
        val iterator = items.iterator()
        return object : MutableIterator<E> {
            override fun hasNext(): Boolean = iterator.hasNext()

            override fun next(): E = iterator.next()

            override fun remove() {
                iterator.remove()
                onChange()
            }
        }
    }

    internal fun readOnlyIterator(): Iterator<E> = items.iterator()
}

@Suppress("UNCHECKED_CAST")
internal fun <E> Set<E>.readOnlyIterator(): Iterator<E> {
    return if (this is InvalidatingMutableSet<*>) {
        (this as InvalidatingMutableSet<E>).readOnlyIterator()
    } else {
        iterator()
    }
}

internal fun <E> Set<E>.copyToStableSet(): Set<E> {
    if (isEmpty()) return emptySet()
    val copy = LinkedHashSet<E>(size)
    val iterator = readOnlyIterator()
    while (iterator.hasNext()) copy += iterator.next()
    return copy
}

internal class InvalidatingMutableMap<K, V>(
    values: Map<K, V> = emptyMap(),
    private val onChange: () -> Unit,
) : AbstractMutableMap<K, V>() {
    private val items = values.toMutableMap()

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = InvalidatingEntrySet(items.entries, onChange)

    override fun containsKey(key: K): Boolean = items.containsKey(key)

    override fun containsValue(value: V): Boolean = items.containsValue(value)

    override fun get(key: K): V? = items[key]

    override fun put(key: K, value: V): V? {
        val hadKey = items.containsKey(key)
        val previous = items.put(key, value)
        if (!hadKey || previous != value) onChange()
        return previous
    }

    override fun remove(key: K): V? {
        if (!items.containsKey(key)) return null
        return items.remove(key).also { onChange() }
    }

    override fun clear() {
        if (items.isEmpty()) return
        items.clear()
        onChange()
    }

    private class InvalidatingEntrySet<K, V>(
        private val entries: MutableSet<MutableMap.MutableEntry<K, V>>,
        private val onChange: () -> Unit,
    ) : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
        override val size: Int
            get() = entries.size

        override fun add(element: MutableMap.MutableEntry<K, V>): Boolean {
            return entries.add(element).also { changed -> if (changed) onChange() }
        }

        override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
            val iterator = entries.iterator()
            return object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                override fun hasNext(): Boolean = iterator.hasNext()

                override fun next(): MutableMap.MutableEntry<K, V> {
                    val entry = iterator.next()
                    return InvalidatingEntry(entry, onChange)
                }

                override fun remove() {
                    iterator.remove()
                    onChange()
                }
            }
        }
    }

    private class InvalidatingEntry<K, V>(
        private val entry: MutableMap.MutableEntry<K, V>,
        private val onChange: () -> Unit,
    ) : MutableMap.MutableEntry<K, V> {
        override val key: K
            get() = entry.key

        override val value: V
            get() = entry.value

        override fun setValue(newValue: V): V {
            val previous = entry.setValue(newValue)
            if (previous != newValue) onChange()
            return previous
        }
    }
}

class UiNodeLayoutState internal constructor() {
    /**
     * Per-node scratch state owned by the style resolver (caches + motion state). Living on the
     * node instead of resolver-side WeakHashMaps removes several weak-map lookups per node per
     * frame and lets the state die with the node. Opaque here to avoid a package cycle.
     */
    internal var styleResolverState: Any? = null

    internal var measureCache: NodeMeasureCache? = null

    private var parent: UiNode? = null
    private var resolvedLayoutFingerprint = 0
    private var localLayoutRevision = UiLayoutRevision.next()
    private var localDrawRevision = localLayoutRevision
    private var localInputRevision = localLayoutRevision
    private var subtreeLayoutRevisionValue = localLayoutRevision
    private var subtreeDrawRevisionValue = localDrawRevision
    private var subtreeInputRevisionValue = localInputRevision

    val layoutRevision: Long
        get() = localLayoutRevision

    val drawRevision: Long
        get() = localDrawRevision

    val inputRevision: Long
        get() = localInputRevision

    val subtreeLayoutRevision: Long
        get() = subtreeLayoutRevisionValue

    val subtreeDrawRevision: Long
        get() = subtreeDrawRevisionValue

    val subtreeInputRevision: Long
        get() = subtreeInputRevisionValue

    val subtreeRevision: Long
        get() = maxOf(subtreeLayoutRevisionValue, subtreeDrawRevisionValue, subtreeInputRevisionValue)

    internal val parentNode: UiNode?
        get() = parent

    internal fun attachTo(nextParent: UiNode?) {
        if (parent === nextParent) return
        parent = nextParent
        invalidateLayout()
    }

    internal fun updateResolvedLayoutFingerprint(fingerprint: Int) {
        if (resolvedLayoutFingerprint == fingerprint) return
        resolvedLayoutFingerprint = fingerprint
        invalidateLayout()
    }

    internal fun invalidateLayout() {
        val nextRevision = UiLayoutRevision.next()
        localLayoutRevision = nextRevision
        markSubtreeLayoutChanged(nextRevision)
    }

    internal fun invalidateDraw() {
        val nextRevision = UiLayoutRevision.next()
        localDrawRevision = nextRevision
        markSubtreeDrawChanged(nextRevision)
    }

    internal fun invalidateInput() {
        val nextRevision = UiLayoutRevision.next()
        localInputRevision = nextRevision
        markSubtreeInputChanged(nextRevision)
    }

    private fun markSubtreeLayoutChanged(nextRevision: Long) {
        if (subtreeLayoutRevisionValue == nextRevision) return
        subtreeLayoutRevisionValue = nextRevision
        parent?.layoutState?.markSubtreeLayoutChanged(nextRevision)
    }

    private fun markSubtreeDrawChanged(nextRevision: Long) {
        if (subtreeDrawRevisionValue == nextRevision) return
        subtreeDrawRevisionValue = nextRevision
        parent?.layoutState?.markSubtreeDrawChanged(nextRevision)
    }

    private fun markSubtreeInputChanged(nextRevision: Long) {
        if (subtreeInputRevisionValue == nextRevision) return
        subtreeInputRevisionValue = nextRevision
        parent?.layoutState?.markSubtreeInputChanged(nextRevision)
    }
}

internal fun UiNode.invalidateLayout() {
    layoutState.invalidateLayout()
}

internal fun UiNode.invalidateDraw() {
    layoutState.invalidateDraw()
}

internal fun UiNode.invalidateInput() {
    layoutState.invalidateInput()
}

internal fun UiNode.detachLayoutParentRecursively() {
    val stack = ArrayDeque<UiNode>()
    stack.add(this)
    while (stack.isNotEmpty()) {
        val node = stack.removeLast()
        node.layoutState.attachTo(null)
        for (index in node.children.indices.reversed()) {
            stack.add(node.children[index])
        }
    }
}

private object UiLayoutRevision {
    private var value = 0L

    fun next(): Long {
        value += 1L
        return value
    }
}
