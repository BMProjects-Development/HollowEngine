package ru.hollowhorizon.hollowengine.addons.narrative.world

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Clearable
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryException
import ru.hollowhorizon.hollowengine.common.utils.currentServer

/**
 * The blocks of a region as they are, with what their block entities hold, written to NBT so an undo can put them
 * back after a restart too.
 */
internal object BlockSnapshot {
    private const val BLOCKS = "blocks"
    private const val DIMENSION = "dimension"
    private const val POS = "pos"
    private const val STATE = "state"
    private const val ENTITY = "entity"

    fun of(level: ServerLevel, positions: Iterable<BlockPos>) = CompoundTag().apply {
        putString(DIMENSION, level.dimension().location().toString())
        put(BLOCKS, ListTag().apply {
            positions.forEach { pos ->
                add(CompoundTag().apply {
                    putLong(POS, pos.asLong())
                    put(STATE, NbtUtils.writeBlockState(level.getBlockState(pos)))
                    level.getBlockEntity(pos)?.let { put(ENTITY, it.saveWithFullMetadata(level.registryAccess())) }
                })
            }
        })
    }

    fun restore(snapshot: CompoundTag) {
        val level = levelOf(snapshot.getString(DIMENSION))
        val blocks = snapshot.getList(BLOCKS, Tag.TAG_COMPOUND.toInt()).map { it as CompoundTag }
        val placed = blocks.map { block ->
            val pos = BlockPos.of(block.getLong(POS))
            val state = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), block.getCompound(STATE))
            place(level, pos, state, Block.UPDATE_CLIENTS)
            block.getCompound(ENTITY).takeUnless { it.isEmpty }?.let { saved ->
                level.getBlockEntity(pos)?.loadWithComponents(saved, level.registryAccess())
            }
            pos to state
        }
        placed.forEach { (pos, state) -> level.blockUpdated(pos, state.block) }
    }

    fun place(level: ServerLevel, pos: BlockPos, state: BlockState, flags: Int = Block.UPDATE_ALL) {
        Clearable.tryClear(level.getBlockEntity(pos))
        level.setBlock(pos, state, flags)
    }

    fun levelOf(dimension: String): ServerLevel =
        currentServer.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)))
            ?: throw StoryException("Dimension '$dimension' does not exist")
}
