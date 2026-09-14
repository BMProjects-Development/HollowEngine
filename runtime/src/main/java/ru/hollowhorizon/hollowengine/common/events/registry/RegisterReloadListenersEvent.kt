package ru.hollowhorizon.hollowengine.common.events.registry

import net.minecraft.server.packs.resources.PreparableReloadListener
import ru.hollowhorizon.hollowengine.common.events.ClientEvent
import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.ServerEvent
import ru.hollowhorizon.hollowengine.common.events.StartupEvent
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

open class RegisterReloadListenersEvent : Event {
    val listeners = HashSet<PreparableReloadListener>()
    fun register(listener: PreparableReloadListener) {
        listeners += listener
    }

    class Client : RegisterReloadListenersEvent(), ClientEvent, StartupEvent {
        companion object : EventHandler<Client>()
    }

    /** Fires once on Fabric and on every datapack load on NeoForge. */
    class Server : RegisterReloadListenersEvent(), ServerEvent {
        companion object : EventHandler<Server>()
    }
}
