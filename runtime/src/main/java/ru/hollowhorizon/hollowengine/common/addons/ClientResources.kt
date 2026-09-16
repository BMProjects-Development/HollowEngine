package ru.hollowhorizon.hollowengine.common.addons

import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.client.ui.HollowUiResourceAccess

object ClientResources {
    fun reload() {
        val minecraft = Minecraft.getInstance()
        minecraft.execute {
            HollowUiResourceAccess.clearCache()
            minecraft.reloadResourcePacks()
        }
    }
}
