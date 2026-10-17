package ru.hollowhorizon.hollowengine.client.render

import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.HideVanillaEntityModelComponent
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderEntityEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderPlayerEvent

@ClientOnly
object VanillaEntityModelVisibilitySystem {
    @SubscribeEvent
    fun onRenderEntity(event: RenderEntityEvent.Pre) {
        if (event.entity.shouldHideVanillaModel()) event.isCanceled = true
    }

    @SubscribeEvent
    fun onRenderPlayer(event: RenderPlayerEvent) {
        if (event.player.shouldHideVanillaModel()) event.isCanceled = true
    }

    private fun Entity.shouldHideVanillaModel(): Boolean {
        val componentId = ComponentDescriptorRegistry.idFor(HideVanillaEntityModelComponent::class) ?: return false
        val component = AttachmentRegistry.componentsById(this)[componentId] as? HideVanillaEntityModelComponent
        return component?.enabled == true
    }
}
