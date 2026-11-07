package ru.hollowhorizon.hollowengine.addons.narrative.world

import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCategory
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryValue
import ru.hollowhorizon.hollowengine.addons.narrative.story.storyLevel
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRegistry

private const val DAY = 24000L
private const val BLOCKS = "minecraft:block"

/** The block at [pos] of [level], the level of the story when left out. */
@EditorCategory(StoryCategory.BLOCKS)
@StoryValue("block-at")
suspend fun blockAt(pos: Vec3, level: ServerLevel? = null): BlockState =
    (level ?: storyLevel()).getBlockState(BlockPos.containing(pos))

/** The id of the block of [block], such as `minecraft:stone`. */
@EditorCategory(StoryCategory.BLOCKS)
@StoryValue("block-id")
fun blockIdOf(block: BlockState): ResourceLocation = BuiltInRegistries.BLOCK.getKey(block.block)

/** Whether [block] belongs to the block tag [tag], such as `minecraft:logs`. */
@EditorCategory(StoryCategory.BLOCKS)
@StoryValue("block-in-tag")
fun blockInTag(block: BlockState, @EditorRegistry(BLOCKS, tags = true) tag: ResourceLocation): Boolean =
    block.`is`(TagKey.create(Registries.BLOCK, tag))

@EditorCategory(StoryCategory.BLOCKS)
@StoryValue("is-air")
fun isAir(block: BlockState): Boolean = block.isAir

/** The time of day of [level], from 0 at sunrise to 24000. */
@EditorCategory(StoryCategory.TIME)
@StoryValue("time-of-day")
suspend fun timeOfDay(level: ServerLevel? = null): Int = (level ?: storyLevel()).dayTime.mod(DAY).toInt()

/** How many days have gone by in [level]. */
@EditorCategory(StoryCategory.TIME)
@StoryValue("day")
suspend fun dayOf(level: ServerLevel? = null): Int = ((level ?: storyLevel()).dayTime / DAY).toInt()

@EditorCategory(StoryCategory.TIME)
@StoryValue("is-day")
suspend fun isDay(level: ServerLevel? = null): Boolean = (level ?: storyLevel()).isDay

@EditorCategory(StoryCategory.WEATHER)
@StoryValue("raining")
suspend fun isRaining(level: ServerLevel? = null): Boolean = (level ?: storyLevel()).isRaining

@EditorCategory(StoryCategory.WEATHER)
@StoryValue("thundering")
suspend fun isThundering(level: ServerLevel? = null): Boolean = (level ?: storyLevel()).isThundering

/** The dimension [level] names, such as `minecraft:the_nether`. */
@EditorCategory(StoryCategory.LEVELS)
@StoryValue("dimension")
fun dimensionValue(level: ServerLevel): ServerLevel = level

/** The id of the dimension of [level], such as `minecraft:overworld`. */
@EditorCategory(StoryCategory.LEVELS)
@StoryValue("dimension-id")
fun dimensionIdOf(level: ServerLevel): ResourceLocation = level.dimension().location()
