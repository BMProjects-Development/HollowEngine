package ru.hollowhorizon.hollowengine.common.npcs.navigation

import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

val Vec3.blockX get() = Mth.floor(x)
val Vec3.blockY get() = Mth.floor(y)
val Vec3.blockZ get() = Mth.floor(z)
val Vec3.block get() = BlockPos(blockX, blockY, blockZ)