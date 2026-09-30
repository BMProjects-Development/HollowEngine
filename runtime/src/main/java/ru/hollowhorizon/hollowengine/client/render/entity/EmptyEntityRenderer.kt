package ru.hollowhorizon.hollowengine.client.render.entity

import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity

open class EmptyEntityRenderer(context: EntityRendererProvider.Context) : EntityRenderer<Entity>(context) {
    override fun getTextureLocation(entity: Entity): ResourceLocation? {
        return MissingTextureAtlasSprite.getLocation()
    }
}
