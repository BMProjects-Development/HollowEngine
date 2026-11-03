package ru.hollowhorizon.hollowengine.addons.lighting.client

import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.addons.lighting.LightInstance
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.entities.objects.WorldObjectEntity
import ru.hollowhorizon.hollowengine.addons.lighting.LightComponent
import kotlin.math.abs

internal object LightComponents {
    fun collect(partialTick: Float, submit: (LightInstance) -> Unit) {
        val level = Minecraft.getInstance().level ?: return
        AttachmentRegistry.allAttachments().forEach { attachments ->
            val entity = attachments.entity
            if (entity.level() !== level || entity.isRemoved) return@forEach
            val component =
                attachments.components.readOnly.values.firstNotNullOfOrNull { it as? LightComponent } ?: return@forEach
            if (!component.light.enabled) return@forEach
            submit(instance(entity, component, partialTick))
        }
    }

    private fun instance(entity: Entity, component: LightComponent, partialTick: Float): LightInstance {
        val objectPose = (entity as? WorldObjectEntity)?.pose(partialTick)
        val position = objectPose?.position?.let { Vec3(it.x, it.y, it.z) } ?: entity.getPosition(partialTick)
        val rotation = objectPose?.rotation ?: Quaternionf().rotationYXZ(
            Math.toRadians(180.0 - entity.getViewYRot(partialTick)).toFloat(),
            Math.toRadians(-entity.getViewXRot(partialTick).toDouble()).toFloat(), 0f,
        )
        val scale = objectPose?.scale ?: Vector3f(1f)
        val offset = Vector3f(component.offset.x, component.offset.y, component.offset.z).mul(scale).rotate(rotation)
        val local = component.rotation
        val direction = Vector3f(0f, 0f, -1f).rotate(
            Quaternionf(rotation).rotateXYZ(
                Math.toRadians(local.x.toDouble()).toFloat(), Math.toRadians(local.y.toDouble()).toFloat(),
                Math.toRadians(local.z.toDouble()).toFloat(),
            ),
        )
        return LightInstance(
            "entity/${entity.uuid}",
            component.light,
            position.x + offset.x,
            position.y + offset.y,
            position.z + offset.z,
            direction.x,
            direction.y,
            direction.z,
            maxOf(abs(scale.x), abs(scale.y), abs(scale.z)),
        )
    }
}
