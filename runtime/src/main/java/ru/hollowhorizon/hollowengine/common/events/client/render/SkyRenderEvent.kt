package ru.hollowhorizon.hollowengine.common.events.client.render

import net.minecraft.client.multiplayer.ClientLevel
import ru.hollowhorizon.hollowengine.common.events.ClientEvent
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

open class SkyRenderEvent(val level: ClientLevel) : ClientEvent {

    class SunSize(level: ClientLevel, var sunSize: Float) : SkyRenderEvent(level) {
        companion object : EventHandler<SunSize>()
    }

    class MoonSize(level: ClientLevel, var moonSize: Float) : SkyRenderEvent(level) {
        companion object : EventHandler<MoonSize>()
    }
}
