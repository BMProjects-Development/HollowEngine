package ru.hollowhorizon.hollowengine.common.scene

import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.scripting.annotations.Attach
import java.util.UUID
import kotlin.reflect.KClass

interface ComponentOwner {
    val scene: Scene
    val isActive: Boolean
    val isRemoved: Boolean
    val components: Map<UUID, Any>
    fun component(id: UUID): Any?
    fun attach(value: Any, id: UUID = UUID.randomUUID(), enabled: Boolean = true): UUID
    fun setComponent(id: UUID, value: Any)
    fun componentEnabled(id: UUID): Boolean
    fun setComponentEnabled(id: UUID, enabled: Boolean)
    fun detach(id: UUID): Boolean
}

inline fun <reified T : Any> ComponentOwner.componentsOfType(): List<T> =
    components.values.filterIsInstance<T>()

private val componentOwnerTypes = object : ClassValue<Class<*>>() {
    override fun computeValue(type: Class<*>): Class<*> =
        type.getAnnotation(Attach::class.java)?.value?.java ?: Any::class.java
}

internal fun KClass<*>.acceptsComponentOwner(owner: Class<*>): Boolean =
    componentOwnerTypes.get(java).isAssignableFrom(owner)

/** Used by the editor, loading, and direct attachment to validate the same owner constraint. */
fun ComponentOwner.accepts(value: Any): Boolean =
    value::class.acceptsComponentOwner(javaClass) && ComponentDescriptorRegistry.descriptorOrNull(value::class) != null

internal class SceneComponentStore(private val owner: ComponentOwner) {
    private class Entry(var data: Any, val lifecycle: ComponentLifecycle?, var enabled: Boolean) {
        var active = false

        fun refresh(context: SceneContext, ownerActive: Boolean, failures: SceneFailures) {
            val next = enabled && ownerActive
            if (active == next) return
            active = next
            val runtime = lifecycle ?: return
            if (!next) {
                failures.capture { context.onLifecycleActivityChanged(runtime, false) }
                failures.capture { runtime.suspend() }
            } else if (!failures.capture {
                runtime.activate()
                context.onLifecycleActivityChanged(runtime, true)
            }) {
                active = false
                enabled = false
                failures.capture { context.onLifecycleActivityChanged(runtime, false) }
                failures.capture { runtime.suspend() }
            }
        }

        fun finish(context: SceneContext, failures: SceneFailures) {
            refresh(context, false, failures)
            failures.capture { lifecycle?.remove() }
        }
    }

    private val entries = linkedMapOf<UUID, Entry>()
    private var snapshot: Map<UUID, Any>? = null
    val components: Map<UUID, Any>
        get() = snapshot ?: buildMap(entries.size) {
            for ((id, entry) in this@SceneComponentStore.entries) put(id, entry.data)
        }.also { snapshot = it }

    fun component(id: UUID): Any? = entries[id]?.data

    fun add(value: Any, id: UUID, enabled: Boolean): UUID {
        require(owner.accepts(value)) {
            "Component ${value::class.simpleName} is unavailable on this owner"
        }
        return insert(value, id, enabled)
    }

    fun restoreUnknown(value: Any, id: UUID) = insert(value, id, enabled = false)

    private fun insert(value: Any, id: UUID, enabled: Boolean): UUID = owner.scene.mutate {
        checkAttached()
        require(id !in entries) { "Duplicate component ID: $id" }
        val entry = Entry(value, owner.scene.context.createLifecycle(owner, id, value), enabled)
        entries[id] = entry
        val failures = SceneFailures()
        changed()
        if (failures.capture { entry.lifecycle?.attach() }) entry.refresh(owner.scene.context, owner.isActive, failures)
        try {
            failures.throwIfAny()
        } catch (exception: Exception) {
            entries.remove(id)
            changed()
            entry.finish(owner.scene.context, failures)
            failures.throwIfAny()
        }
        id
    }

    fun set(id: UUID, value: Any) = owner.scene.mutate {
        checkAttached()
        val entry = requireNotNull(entries[id]) { "Unknown component $id" }
        require(value::class == entry.data::class)
        if (entry.data == value) return@mutate
        entry.data = value
        changed()
        val failures = SceneFailures()
        if (!failures.capture { entry.lifecycle?.change(value) }) {
            entry.enabled = false
            entry.refresh(owner.scene.context, owner.isActive, failures)
            failures.throwIfAny()
        }
    }

    fun enabled(id: UUID): Boolean = entries[id]?.enabled == true

    fun setEnabled(id: UUID, enabled: Boolean) = owner.scene.mutate {
        checkAttached()
        val entry = requireNotNull(entries[id]) { "Unknown component $id" }
        if (entry.enabled == enabled) return@mutate
        entry.enabled = enabled
        val failures = SceneFailures()
        entry.refresh(owner.scene.context, owner.isActive, failures)
        changed()
        failures.throwIfAny()
    }

    fun remove(id: UUID): Boolean = owner.scene.mutate {
        checkAttached()
        val entry = entries.remove(id) ?: return@mutate false
        changed()
        val failures = SceneFailures()
        entry.finish(owner.scene.context, failures)
        failures.throwIfAny()
        true
    }

    fun refresh(failures: SceneFailures, active: Boolean) {
        val ordered = if (active) entries.values else entries.values.toList().asReversed()
        ordered.forEach { it.refresh(owner.scene.context, active, failures) }
    }

    fun finish(failures: SceneFailures) {
        entries.values.toList().asReversed().forEach { it.finish(owner.scene.context, failures) }
        entries.clear()
        snapshot = null
    }

    private fun changed() {
        snapshot = null
        owner.scene.markChanged()
    }

    private fun checkAttached() = check(!owner.isRemoved && !owner.scene.isClosed) { "Any owner has been removed" }
}
