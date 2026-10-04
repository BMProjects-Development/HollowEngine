package ru.hollowhorizon.hollowengine.client.vfx

import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.joml.Quaternionf
import ru.hollowhorizon.hollowengine.client.render.RenderManager
import ru.hollowhorizon.hollowengine.client.render.hostRenderShift
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.vfxComponent
import ru.hollowhorizon.hollowengine.common.colliders.hostRotation
import ru.hollowhorizon.hollowengine.common.colliders.hostScale
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderEntityEvent

/**
 * Plays the effect of each entity's [ru.hollowhorizon.hollowengine.common.attachments.components.VfxComponent]
 * where the entity is drawn. The playing effect lives in the entity's runtime attachments, so it ends with
 * the entity and starts over when it comes back into view.
 */
@ClientOnly
object EntityVfx {
    private object Key

    @SubscribeEvent
    fun onRenderEntity(event: RenderEntityEvent.Pre) {
        if (!RenderManager.isWorldPass) return
        val entity = event.entity
        val component = entity.vfxComponent ?: return
        if (component.effect.isBlank()) return

        val runtime = AttachmentRegistry.attachments(entity).runtime
        if (runtime.getOrNull<VfxBoundEffect>(Key)?.asset != component.effect) runtime.remove(Key)
        val effect = runtime.getOrPut(Key) { VfxBoundEffect(component.effect) }
        if (!effect.claimFrame()) return

        val partialTick = event.partialTicks
        val shift = hostRenderShift(entity, partialTick)
        val rotation = hostRotation(entity, partialTick)
        val scale = hostScale(entity, partialTick)
        val offset = component.offset
        effect.placement.set(event.poseStack.last().pose())
            .translate(shift.x.toFloat(), shift.y.toFloat(), shift.z.toFloat())
            .rotate(Quaternionf(rotation.x, rotation.y, rotation.z, rotation.w))
            .scale(scale.x, scale.y, scale.z)
            .translate(offset.x, offset.y, offset.z)
        VfxBoneBindings.submit { camera -> effect.advance(camera, Matrix4f(effect.placement)) }
    }

    /** A pack reload replaces every effect definition, so the effects playing on entities start over. */
    fun onAssetsReloaded() {
        val level = Minecraft.getInstance().level ?: return
        level.entitiesForRendering().forEach { entity ->
            AttachmentRegistry.attachmentsOrNull(entity)?.runtime?.remove(Key)
        }
    }
}
