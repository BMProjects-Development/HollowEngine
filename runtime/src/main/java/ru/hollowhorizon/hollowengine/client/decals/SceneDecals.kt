package ru.hollowhorizon.hollowengine.client.decals

import org.joml.Matrix4f
import org.joml.Matrix4fc
import ru.hollowhorizon.hollowengine.client.scene.SceneComponentKey
import ru.hollowhorizon.hollowengine.client.scene.SceneDrawSystem
import ru.hollowhorizon.hollowengine.client.utils.math.copyTo
import ru.hollowhorizon.hollowengine.common.decals.Decal
import ru.hollowhorizon.hollowengine.common.scene.ComponentActivity

/** Collects decal data from active bindings. Projection and textures belong to the renderer. */
internal class SceneDecals : SceneDrawSystem {
    private val active = LinkedHashSet<SceneComponentKey>()

    override val hasDraws: Boolean get() = active.isNotEmpty()

    override fun onStateChanged(binding: SceneComponentKey, state: ComponentActivity) {
        if (state == ComponentActivity.ACTIVE) active.add(binding) else active.remove(binding)
    }

    override fun collect(sceneToView: Matrix4fc, light: Int) {
        for (binding in active) {
            val node = binding.owner
            if (!node.modelVisible) continue
            val decal = binding.value as? Decal ?: continue
            Decals.submitComponent(binding) { capturing ->
                val placement = node.worldTransform.copyTo(Matrix4f()).mulLocal(sceneToView)
                val key = if (capturing) {
                    val bone = if (node.modelSource != null) node.poseName else ""
                    "$bone/${decal.id}"
                } else null
                DecalDraw(decal, placement, light, key = key)
            }
        }
    }
}
