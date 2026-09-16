package ru.hollowhorizon.hollowengine.common.events.registry

import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.repository.Pack
import ru.hollowhorizon.hollowengine.client.utils.asPack
import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import java.util.function.Consumer

class RegisterResourcePacksEvent(private val resourcePacks: Consumer<Pack>) : Event {
    companion object : EventHandler<RegisterResourcePacksEvent>()

    fun addPack(pack: PackResources) {
        resourcePacks.accept(pack.asPack() ?: error("Resource packs not found!"))
    }

    fun addPack(pack: Pack) {
        resourcePacks.accept(pack)
    }
}