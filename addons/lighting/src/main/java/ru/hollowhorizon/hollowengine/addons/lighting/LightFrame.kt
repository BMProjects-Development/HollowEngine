package ru.hollowhorizon.hollowengine.addons.lighting

import kotlin.math.max
import kotlin.math.sqrt
import org.qualet.irl.light.LightBuffer
import ru.hollowhorizon.hollowengine.addons.lighting.LightSpec

/** Position stays double precision until it is made relative to the current frame's camera. */
data class LightInstance(
    val id: String,
    val light: LightSpec,
    val x: Double,
    val y: Double,
    val z: Double,
    val directionX: Float = 0f,
    val directionY: Float = 0f,
    val directionZ: Float = -1f,
    val scale: Float = 1f,
) {
    val isValid: Boolean
        get() = id.isNotBlank() && x.isFinite() && y.isFinite() && z.isFinite() &&
            directionX.isFinite() && directionY.isFinite() && directionZ.isFinite() &&
            scale.isFinite() && scale > 0f && (light.radius * scale).isFinite()
}

/** Camera-independent source seam for scripted, component and attachment lights. */
fun interface LightSource {
    fun collect(partialTick: Float, submit: (LightInstance) -> Unit)
}

/** Stable selection before registration into the shared IRLights GPU pipeline. */
object LightFrame {
    const val MAX_LIGHTS = LightBuffer.MAX_LIGHTS

    fun select(lights: Iterable<LightInstance>, x: Double, y: Double, z: Double, limit: Int = MAX_LIGHTS): List<LightInstance> {
        require(limit in 0..MAX_LIGHTS)
        return lights.asSequence()
            .filter { it.isValid && it.light.enabled && it.light.intensity > 0f }
            .distinctBy(LightInstance::id)
            .sortedWith(compareBy<LightInstance>({
                val distance = sqrt((it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) + (it.z - z) * (it.z - z))
                max(0.0, distance - it.light.radius * it.scale)
            }, LightInstance::id))
            .take(limit)
            .toList()
    }

}
