package ru.hollowhorizon.hollowengine.client.scene

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4dc
import org.joml.Matrix4fc
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.scene.ComponentLifecycle
import ru.hollowhorizon.hollowengine.common.scene.ComponentOwner
import ru.hollowhorizon.hollowengine.common.scene.SceneContext
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import java.util.*
import kotlin.reflect.KClass

/** Submits draw data for the current view. Collection leaves the component's playback state unchanged. */
fun interface RenderComponent {
    fun collect(sceneToView: Matrix4fc)
}

/** Optional client update, run after the scene has applied its pose. */
interface ClientComponent : ComponentLifecycle {
    val requiresAnimationVariables: Boolean get() = false
    fun update(frame: ComponentUpdate)
}

/** Inputs borrowed from the host for the duration of one update. */
sealed interface ComponentUpdate {
    class World internal constructor() : ComponentUpdate {
        lateinit var sceneToWorld: Matrix4dc
            internal set
        lateinit var entity: Entity
            internal set
        lateinit var scope: String
            internal set
        lateinit var camera: Vec3
            internal set
        var animation: AnimatorEvaluationContext? = null
            internal set
    }

    class Preview internal constructor() : ComponentUpdate {
        var playSounds: Boolean = false
            internal set
        var effectTime: Float? = null
            internal set
        var effectEmitting: ((bone: String?, id: String, time: Float) -> Boolean)? = null
            internal set
    }
}

class ClientSceneContext : SceneContext {
    override val side = LogicalSide.CLIENT
    private var renderers: MutableSet<RenderComponent>? = null
    private var updates: MutableSet<ClientComponent>? = null
    private var world: ComponentUpdate.World? = null
    private var preview: ComponentUpdate.Preview? = null

    val hasDraws: Boolean get() = !renderers.isNullOrEmpty()
    val hasUpdates: Boolean get() = !updates.isNullOrEmpty()
    val needsAnimationVariables: Boolean get() = updates?.any { it.requiresAnimationVariables } == true

    override fun createLifecycle(owner: ComponentOwner, id: UUID, value: Any): ComponentLifecycle? =
        super.createLifecycle(owner, id, value) ?: (owner as? SceneObject)?.let {
            factories[value::class]?.invoke(
                it,
                id,
                value
            )
        }

    override fun onLifecycleActivityChanged(lifecycle: ComponentLifecycle, active: Boolean) {
        if (active) {
            if (lifecycle is RenderComponent) {
                val draws = renderers ?: LinkedHashSet<RenderComponent>().also { renderers = it }
                draws.add(lifecycle)
            }
            if (lifecycle is ClientComponent) {
                val updatables = updates ?: LinkedHashSet<ClientComponent>().also { updates = it }
                updatables.add(lifecycle)
            }
        } else {
            if (lifecycle is RenderComponent) renderers?.remove(lifecycle)
            if (lifecycle is ClientComponent) updates?.remove(lifecycle)
        }
    }

    fun updateWorld(
        sceneToWorld: Matrix4dc,
        entity: Entity,
        scope: String,
        camera: Vec3,
        animation: AnimatorEvaluationContext?,
    ) {
        if (!hasUpdates) return
        val frame = world ?: ComponentUpdate.World().also { world = it }
        frame.sceneToWorld = sceneToWorld
        frame.entity = entity
        frame.scope = scope
        frame.camera = camera
        frame.animation = animation
        try {
            for (component in updates.orEmpty()) component.update(frame)
        } finally {
            frame.animation = null
        }
    }

    fun collect(sceneToView: Matrix4fc) {
        for (renderer in renderers.orEmpty()) renderer.collect(sceneToView)
    }

    fun updatePreview(
        playSounds: Boolean,
        effectTime: Float?,
        effectEmitting: ((bone: String?, id: String, time: Float) -> Boolean)?,
    ) {
        if (!hasUpdates) return
        val frame = preview ?: ComponentUpdate.Preview().also { preview = it }
        frame.playSounds = playSounds
        frame.effectTime = effectTime
        frame.effectEmitting = effectEmitting
        try {
            for (component in updates.orEmpty()) component.update(frame)
        } finally {
            frame.effectEmitting = null
        }
    }

    companion object {
        private val factories = HashMap<KClass<*>, (SceneObject, UUID, Any) -> ComponentLifecycle>()

        fun <T : Any> register(type: KClass<T>, create: (SceneObject, UUID, T) -> ComponentLifecycle): AutoCloseable {
            require(type !in factories) { "Duplicate client runtime for ${type.simpleName}" }
            val factory: (SceneObject, UUID, Any) -> ComponentLifecycle = { owner, id, value ->
                create(owner, id, type.java.cast(value))
            }
            factories[type] = factory
            return AutoCloseable { factories.remove(type, factory) }
        }
    }
}
