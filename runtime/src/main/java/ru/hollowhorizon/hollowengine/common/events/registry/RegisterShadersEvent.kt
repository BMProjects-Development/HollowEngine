package ru.hollowhorizon.hollowengine.common.events.registry

import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.ShaderInstance
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.events.ClientEvent
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

class RegisterShadersEvent : ClientEvent {
    companion object : EventHandler<RegisterShadersEvent>()

    val shaders = hashMapOf<ResourceLocation, Pair<VertexFormat, (ShaderInstance) -> Unit>>()

    fun register(location: ResourceLocation, format: VertexFormat, consumer: (ShaderInstance) -> Unit) {
        shaders += location to Pair(format, consumer)
    }
}