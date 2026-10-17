package ru.hollowhorizon.hollowengine.client.particles.light

import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.client.utils.math.floor
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

class WorldLightProvider(private val world: Level) : LightProvider {
    override fun query(pos: Vec3f): Int {
        val block = with(pos.floor()) { BlockPos(x.toInt(), y.toInt(), z.toInt()) }
        if (!world.isLoaded(block)) return LightTexture.FULL_BRIGHT

        return LevelRenderer.getLightColor(world, block)
    }
}