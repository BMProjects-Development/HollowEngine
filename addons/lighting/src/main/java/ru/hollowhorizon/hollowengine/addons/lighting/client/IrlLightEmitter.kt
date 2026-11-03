package ru.hollowhorizon.hollowengine.addons.lighting.client

import org.qualet.irl.light.LightMath
import org.qualet.irl.light.LightRegistry
import ru.hollowhorizon.hollowengine.addons.lighting.LightInstance
import ru.hollowhorizon.hollowengine.common.lighting.LightKind
import ru.hollowhorizon.hollowengine.common.lighting.LightTarget

internal object IrlLightEmitter {
    fun emit(instance: LightInstance, identity: Long) {
        val light = instance.light
        val entities = light.target == LightTarget.ENTITIES
        val blocks = light.target == LightTarget.BLOCKS
        val radius = light.radius * instance.scale
        val bulb = light.sourceSize * instance.scale
        if (light.kind == LightKind.POINT) {
            LightRegistry.registerPoint(
                instance.x, instance.y, instance.z, light.color.x, light.color.y, light.color.z,
                light.intensity, radius, entities, blocks, light.anisotropy, light.density, light.beam, bulb,
                light.shadows, identity,
            )
        } else {
            val direction = LightMath.normalizeDir(
                instance.directionX,
                instance.directionY,
                instance.directionZ,
                0f,
                0f,
                -1f,
                FloatArray(3)
            )
            val cone = LightMath.cone(light.outerAngle, light.innerAngle)
            LightRegistry.registerSpot(
                instance.x, instance.y, instance.z, direction[0], direction[1], direction[2],
                light.color.x, light.color.y, light.color.z, light.intensity, radius, cone.cosOuter(), cone.cosInner(),
                entities, blocks, light.anisotropy, light.density, light.beam, bulb, light.shadows,
                LightCookies.resolve(light.cookie).toFloat(), Math.toRadians(light.cookieRotation.toDouble()).toFloat(),
                light.cookieScale, if (light.cookieInvert) 1f else 0f, identity,
            )
        }
    }
}
