package ru.hollowhorizon.hollowengine.client.ui.ide.preview

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.fabric.internal.IrisHelper
import kotlin.math.roundToInt

internal fun fadeIntersectingEntity(
    camera: Vec3,
    entity: Entity,
    partialTick: Float,
    buffers: MultiBufferSource,
): MultiBufferSource {
    val opacity = previewEntityOpacity(camera, entity, partialTick)
    if (opacity == 1f) return buffers
    return MultiBufferSource { type ->
        val textured = type as? RenderType.CompositeRenderType
        val texture = textured?.takeIf { it.format() == DefaultVertexFormat.NEW_ENTITY && !it.isOutline }
            ?.state()?.textureState?.cutoutTexture()?.orElse(null)
        if (texture == null) buffers.getBuffer(type)
        else FadedVertices(buffers.getBuffer(RenderType.entityTranslucent(texture)), opacity)
    }
}

internal fun previewEntityOpacity(camera: Vec3, entity: Entity, partialTick: Float): Float {
    if (IrisHelper.isShadowRendering()) return 1f
    val offset = entity.getPosition(partialTick).subtract(entity.position())
    return if (entity.boundingBox.move(offset).inflate(0.05).contains(camera)) 0.25f else 1f
}

private class FadedVertices(private val source: VertexConsumer, private val opacity: Float) : VertexConsumer {
    override fun addVertex(x: Float, y: Float, z: Float) = apply { source.addVertex(x, y, z) }
    override fun setColor(red: Int, green: Int, blue: Int, alpha: Int) = apply {
        source.setColor(red, green, blue, (alpha * opacity).roundToInt())
    }

    override fun setUv(u: Float, v: Float) = apply { source.setUv(u, v) }
    override fun setUv1(u: Int, v: Int) = apply { source.setUv1(u, v) }
    override fun setUv2(u: Int, v: Int) = apply { source.setUv2(u, v) }
    override fun setNormal(x: Float, y: Float, z: Float) = apply { source.setNormal(x, y, z) }
}
