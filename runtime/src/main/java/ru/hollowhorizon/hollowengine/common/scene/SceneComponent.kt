package ru.hollowhorizon.hollowengine.common.scene

import kotlinx.serialization.KSerializer
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentPersistencePolicy
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentSyncPolicy
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import java.util.UUID
import kotlin.reflect.KClass

/**
 * A type describes serializable data; its optional runtime belongs to one component instance.
 * Dependencies require a component of each listed type on the same owner, irrespective of activation.
 */
class SceneComponentType<T : Any>(
    val id: ResourceLocation,
    val valueClass: KClass<T>,
    val serializer: KSerializer<T>,
    val dataVersion: Int = 1,
    val multiple: Boolean = false,
    sides: Set<LogicalSide> = LogicalSide.entries.toSet(),
    val persistence: ComponentPersistencePolicy = ComponentPersistencePolicy.PERSIST,
    val sync: ComponentSyncPolicy = ComponentSyncPolicy.NONE,
    requires: Set<ResourceLocation> = emptySet(),
    val accepts: (SceneComponentOwner) -> Boolean = { true },
    val createRuntime: (SceneComponent<T>) -> SceneComponentRuntime<T>? = { null },
) {
    val sides: Set<LogicalSide> = sides.toSet()
    val requires: Set<ResourceLocation> = requires.toSet()

    init {
        require(dataVersion > 0) { "Component data version must be positive" }
        require(sides.isNotEmpty()) { "A component type must support at least one side" }
        require(id !in requires) { "A component cannot depend on its own type" }
    }
}

/**
 * Callbacks run synchronously on the scene's thread. Scene mutations from callbacks are rejected.
 * A failed activation or data change disables the component and attempts to suspend its runtime.
 * During removal all callbacks are attempted, even if an earlier callback fails.
 */
interface SceneComponentRuntime<T : Any> {
    fun onAttach(component: SceneComponent<T>) = Unit
    fun onActivate(component: SceneComponent<T>) = Unit
    fun onChange(component: SceneComponent<T>, previous: T) = Unit
    fun onSuspend(component: SceneComponent<T>) = Unit
    fun onRemove(component: SceneComponent<T>) = Unit
}

class SceneComponent<T : Any> internal constructor(
    val owner: SceneComponentOwner,
    val id: UUID,
    val type: SceneComponentType<T>,
    data: T,
) {
    var data: T = data
        private set
    var enabled: Boolean = true
        private set
    var isActive: Boolean = false
        private set
    var isRemoved: Boolean = false
        private set
    internal var runtime: SceneComponentRuntime<T>? = null

    /** Replace data through this method so the runtime sees the change; treat data values as immutable. */
    fun setData(value: T) = owner.scene.mutate {
        checkAttached()
        require(type.valueClass.isInstance(value)) { "Data does not match component type ${type.id}" }
        val previous = data
        data = value
        val failures = SceneFailures()
        if (!failures.capture { runtime?.onChange(this, previous) }) {
            enabled = false
            refreshActive(failures)
            failures.throwIfAny()
        }
    }

    fun setEnabled(value: Boolean) = owner.scene.mutate {
        checkAttached()
        if (enabled != value) {
            enabled = value
            val failures = SceneFailures()
            refreshActive(failures)
            failures.throwIfAny()
        }
    }

    fun remove() = owner.removeComponent(id)

    internal fun refreshActive(failures: SceneFailures) {
        val active = !isRemoved && enabled && owner.isActive
        if (isActive == active) return
        isActive = active
        if (active) {
            if (!failures.capture { runtime?.onActivate(this) }) {
                isActive = false
                enabled = false
                failures.capture { runtime?.onSuspend(this) }
            }
        } else {
            failures.capture { runtime?.onSuspend(this) }
        }
    }

    internal fun finish(failures: SceneFailures) {
        if (isRemoved) return
        if (isActive) {
            isActive = false
            failures.capture { runtime?.onSuspend(this) }
        }
        isRemoved = true
        failures.capture { runtime?.onRemove(this) }
        runtime = null
    }

    private fun checkAttached() = check(!isRemoved && !owner.isRemoved) { "Component has been removed" }
}

internal class SceneFailures {
    private var first: Exception? = null

    fun capture(action: () -> Unit): Boolean {
        return try {
            action()
            true
        } catch (exception: Exception) {
            val previous = first
            if (previous == null) first = exception else if (previous !== exception) previous.addSuppressed(exception)
            false
        }
    }

    fun throwIfAny() {
        first?.let { throw it }
    }
}
