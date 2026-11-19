package ru.hollowhorizon.hollowengine.client.scene

import net.minecraft.client.renderer.LightTexture
import org.joml.Matrix4fc
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.scene.ComponentActivity
import ru.hollowhorizon.hollowengine.common.scene.ComponentOwner
import ru.hollowhorizon.hollowengine.common.scene.SceneContext
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import java.util.UUID
import kotlin.reflect.KClass

/** One client-side system instance handles all bindings of its registered type in a scene. */
fun interface SceneComponentSystem {
    /**
     * Starts work on activation, pauses on suspension and releases resources on removal.
     * The system reads current component data from [binding] when it needs it.
     */
    fun onStateChanged(binding: SceneComponentKey, state: ComponentActivity)
}

/** Submits draw data without advancing simulation. A frame may collect several views. */
interface SceneDrawSystem : SceneComponentSystem {
    val hasDraws: Boolean
    fun collect(sceneToView: Matrix4fc, light: Int)
}

class ClientSceneContext : SceneContext {
    override val side = LogicalSide.CLIENT
    private var systems: MutableMap<KClass<*>, SceneComponentSystem>? = null
    private var drawSystems: MutableList<SceneDrawSystem>? = null

    val hasDraws: Boolean get() = drawSystems?.any { it.hasDraws } == true

    override fun onComponentStateChanged(owner: ComponentOwner, id: UUID, value: Any, state: ComponentActivity) {
        if (owner !is SceneObject) return
        val type = value::class
        val system = systems?.get(type) ?: run {
            if (state != ComponentActivity.ACTIVE) return
            val create = systemFactories[type] ?: return
            val entries = systems ?: HashMap<KClass<*>, SceneComponentSystem>().also { systems = it }
            create().also {
                entries[type] = it
                if (it is SceneDrawSystem) {
                    val draws = drawSystems ?: ArrayList<SceneDrawSystem>().also { list -> drawSystems = list }
                    draws.add(it)
                }
            }
        }
        system.onStateChanged(SceneComponentKey(owner, id), state)
    }

    /** Returns the system initialized for [type] in this scene, if any binding has activated it. */
    fun systemFor(type: KClass<*>): SceneComponentSystem? = systems?.get(type)

    fun collect(sceneToView: Matrix4fc, light: Int = LightTexture.FULL_BRIGHT) {
        for (system in drawSystems.orEmpty()) {
            if (system.hasDraws) system.collect(sceneToView, light)
        }
    }

    companion object {
        private val systemFactories = HashMap<KClass<*>, () -> SceneComponentSystem>()

        /**
         * Registers a factory on the client thread before creating scenes that use [type].
         * Closing the registration removes the factory. Existing scenes retain their systems until they close.
         */
        fun registerSystem(type: KClass<*>, create: () -> SceneComponentSystem): AutoCloseable {
            require(systemFactories.putIfAbsent(type, create) == null) { "Duplicate scene system for ${type.simpleName}" }
            return AutoCloseable { systemFactories.remove(type, create) }
        }
    }
}
