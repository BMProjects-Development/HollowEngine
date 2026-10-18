package ru.hollowhorizon.hollowengine.client.render

import net.minecraft.client.Minecraft
import net.minecraft.world.phys.EntityHitResult
import ru.hollowhorizon.hollowengine.common.attachments.components.NameplateMode
import ru.hollowhorizon.hollowengine.common.attachments.components.nameplateComponent
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderEntityNameplateEvent

object NameplateVisibilitySystem {
    @SubscribeEvent
    fun onRenderNameplate(event: RenderEntityNameplateEvent) {
        when (event.entity.nameplateComponent?.mode ?: return) {
            NameplateMode.HIDDEN -> event.isVisible = false
            NameplateMode.SHOW -> Unit
            NameplateMode.SHOW_ON_HOVER -> {
                val target = Minecraft.getInstance().hitResult as? EntityHitResult
                event.isVisible = target?.entity === event.entity
            }
        }
    }
}