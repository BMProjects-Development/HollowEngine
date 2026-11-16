package ru.hollowhorizon.hollowengine.common.scene

import java.util.UUID

/** A scene host need not have a spatial transform. */
interface SceneComponentOwner {
    val scene: Scene
    val isActive: Boolean
    val isRemoved: Boolean
    val components: List<SceneComponent<*>>
    fun <T : Any> addComponent(type: SceneComponentType<T>, data: T, id: UUID = UUID.randomUUID()): SceneComponent<T>
    fun component(id: UUID): SceneComponent<*>?
    fun <T : Any> components(type: SceneComponentType<T>): List<SceneComponent<T>>
    fun <T : Any> component(type: SceneComponentType<T>): SceneComponent<T>?
    fun removeComponent(id: UUID): Boolean
}

internal class SceneComponentSet(private val owner: SceneComponentOwner) {
    private val values = linkedMapOf<UUID, SceneComponent<*>>()
    val components: List<SceneComponent<*>> get() = values.values.toList()

    fun <T : Any> add(type: SceneComponentType<T>, data: T, id: UUID): SceneComponent<T> = owner.scene.mutate {
        checkAttached()
        require(id !in values) { "Duplicate component ID: $id" }
        require(type.valueClass.isInstance(data)) { "Data does not match component type ${type.id}" }
        require(owner.scene.context.side in type.sides) { "Component ${type.id} is unavailable on ${owner.scene.context.side}" }
        require(type.accepts(owner)) { "Component ${type.id} is not permitted on its owner" }
        require(type.multiple || values.values.none { it.type.id == type.id }) {
            "Component ${type.id} does not allow multiple instances"
        }
        require(type.requires.all { dependency -> values.values.any { it.type.id == dependency } }) {
            "Component ${type.id} is missing required components"
        }
        owner.scene.registerType(type)
        val component = SceneComponent(owner, id, type, data)
        component.runtime = type.createRuntime(component)
        values[id] = component
        try {
            component.runtime?.onAttach(component)
            val failures = SceneFailures()
            component.refreshActive(failures)
            failures.throwIfAny()
        } catch (exception: Exception) {
            values.remove(id)
            val failures = SceneFailures()
            component.finish(failures)
            try {
                failures.throwIfAny()
            } catch (cleanup: Exception) {
                if (cleanup !== exception) exception.addSuppressed(cleanup)
            }
            throw exception
        }
        component
    }

    fun component(id: UUID): SceneComponent<*>? = values[id]

    fun <T : Any> components(type: SceneComponentType<T>): List<SceneComponent<T>> = values.values
        .filter { it.type === type }
        .map {
            @Suppress("UNCHECKED_CAST")
            it as SceneComponent<T>
        }

    fun remove(id: UUID): Boolean = owner.scene.mutate {
        checkAttached()
        val component = values[id] ?: return@mutate false
        if (values.values.count { it.type.id == component.type.id } == 1) {
            require(values.values.none { component.type.id in it.type.requires }) {
                "Component ${component.type.id} is required by another component"
            }
        }
        values.remove(id)
        val failures = SceneFailures()
        component.finish(failures)
        failures.throwIfAny()
        true
    }

    fun refresh(failures: SceneFailures, active: Boolean) {
        val ordered = values.values.toList().let { if (active) it else it.asReversed() }
        ordered.forEach { it.refreshActive(failures) }
    }

    fun finish(failures: SceneFailures) {
        values.values.toList().asReversed().forEach { it.finish(failures) }
        values.clear()
    }

    private fun checkAttached() = check(!owner.isRemoved && !owner.scene.isClosed) { "Component owner has been removed" }
}
