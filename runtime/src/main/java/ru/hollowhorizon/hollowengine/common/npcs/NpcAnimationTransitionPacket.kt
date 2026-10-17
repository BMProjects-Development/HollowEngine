package ru.hollowhorizon.hollowengine.common.npcs

import kotlinx.serialization.Serializable
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.*
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.models.AnimationPlayMode
import ru.hollowhorizon.hollowengine.common.models.ClipAnimationLayerSpec
import ru.hollowhorizon.hollowengine.common.models.ServerModelAnimationMetadata
import ru.hollowhorizon.hollowengine.common.network.HollowPacket
import ru.hollowhorizon.hollowengine.common.network.HollowPacketHandler
import kotlin.math.ceil

@HollowPacketHandler(HollowPacketHandler.Direction.TO_SERVER)
@Serializable
class NpcAnimationTransitionPacket(
    val entityId: Int,
    val from: String? = null,
    val to: String? = null,
    val duration: Float = 0.33f,
    val playMode: AnimationPlayMode = AnimationPlayMode.Once,
    val fadeIn: Float = 0.33f,
    val fadeOut: Float = 0.33f,
) : HollowPacket {
    override fun handle(player: Player) {
        if (player !is ServerPlayer) return
        val level = player.level()
        val entity = level.getEntity(entityId) ?: return

        NpcAnimationRuntime.apply(entity, from, to, playMode, duration, fadeIn, fadeOut)
    }
}

@SubscribeEvent
fun onNpcAnimationServerTick(event: TickEvent.Server) {
    NpcAnimationRuntime.tick(event.server)
}

object NpcAnimationRuntime {
    /** Clips already reported missing, so a script playing one in a loop warns once. */
    private val reportedMissing = HashSet<Pair<String, String>>()

    /** The model [entity] shows, when a component says which one. */
    fun modelOf(entity: Entity): String? =
        AttachmentRegistry.componentsById(entity).values.filterIsInstance<Model>().firstOrNull()?.model

    /** The clip names of [entity]'s model, or null when the entity has no model the server can read. */
    fun animationNames(entity: Entity): Set<String>? =
        modelOf(entity)?.let { model -> ServerModelAnimationMetadata.model(model)?.animationsByName?.keys }

    fun animationDuration(entity: Entity, animation: String): Float? {
        val model = modelOf(entity) ?: return null
        return ServerModelAnimationMetadata.animationDuration(model, animation)
    }

    fun apply(
        entity: Entity,
        from: String?,
        to: String?,
        playMode: AnimationPlayMode,
        duration: Float = DEFAULT_FADE_DURATION,
        fadeIn: Float = DEFAULT_FADE_DURATION,
        fadeOut: Float = DEFAULT_FADE_DURATION,
    ) {
        val animationsId = ComponentDescriptorRegistry.idFor(AnimationsComponent::class) ?: return
        val components = AttachmentRegistry.componentsById(entity)
        val current = components[animationsId] as? AnimationsComponent ?: AnimationsComponent()
        val withoutOld = from?.let { current.fadeOutClip(entity.level().gameTime, it, duration) } ?: current
        val model = components.values.filterIsInstance<Model>().firstOrNull()?.model
        if (to != null && model != null) warnIfMissing(model, to)
        val gameTime = entity.level().gameTime
        val updated = to
            ?.takeIf(String::isNotBlank)
            ?.let { animation ->
                val id = "npc:$animation"
                withoutOld.withClip(
                    ClipAnimationLayerSpec(
                        id = id,
                        animation = animation,
                        playMode = playMode,
                        fadeIn = fadeIn,
                        fadeOut = fadeOut,
                        removeOnEnd = playMode == AnimationPlayMode.Once,
                        removeAtGameTime = completionGameTime(gameTime, model, animation, playMode, fadeOut),
                        startGameTime = withoutOld.continuedStart(id, playMode) ?: gameTime,
                    )
                )
            } ?: withoutOld

        components[animationsId] = updated
    }

    /** A clip the model lacks plays as nothing at all; say so, with the names that would have worked. */
    private fun warnIfMissing(model: String, animation: String) {
        val clips = ServerModelAnimationMetadata.model(model)?.animationsByName?.keys ?: return
        if (animation in clips || !reportedMissing.add(model to animation)) return
        HollowEngine.LOGGER.warn(
            "Model {} has no animation '{}', so nothing plays. Its animations: {}",
            model, animation, clips.sorted().joinToString(),
        )
    }

    fun removeLayer(entity: Entity, layerId: String) {
        val animationsId = ComponentDescriptorRegistry.idFor(AnimationsComponent::class) ?: return
        val components = AttachmentRegistry.componentsById(entity)
        val current = components[animationsId] as? AnimationsComponent ?: return
        val updated = current.withoutLayer(layerId)
        if (updated == current) return

        components[animationsId] = updated
    }

    fun clear(entity: Entity) {
        val animationsId = ComponentDescriptorRegistry.idFor(AnimationsComponent::class) ?: return
        val components = AttachmentRegistry.componentsById(entity)
        val current = components[animationsId] as? AnimationsComponent ?: return
        if (current.clips.isEmpty()) return

        components[animationsId] = AnimationsComponent()
    }

    fun tick(server: MinecraftServer) {
        server.allLevels.forEach(::removeExpiredLayers)
    }

    private fun removeExpiredLayers(level: ServerLevel) {
        val animationsId = ComponentDescriptorRegistry.idFor(AnimationsComponent::class) ?: return
        val now = level.gameTime
        AttachmentRegistry.entitySnapshots(level).forEach { (entity, snapshot) ->
            val animations = snapshot.components.filterIsInstance<AnimationsComponent>().firstOrNull() ?: return@forEach
            val updated = animations.copy(
                clips = animations.clips.filterNot { clip -> clip.removeAtGameTime?.let { it <= now } == true },
                montages = animations.montages.filterNot { montage -> montage.removeAtGameTime?.let { it <= now } == true },
            )
            if (updated == animations) return@forEach

            AttachmentRegistry.componentsById(entity)[animationsId] = updated
        }
    }

    /**
     * Where a repeating clip that is already playing started, so playing it again carries on instead of
     * jumping back to the first frame. A one-shot always starts over.
     */
    private fun AnimationsComponent.continuedStart(id: String, playMode: AnimationPlayMode): Long? {
        if (playMode == AnimationPlayMode.Once) return null
        return clips.firstOrNull { it.id == id && it.playMode == playMode && it.stopAtGameTime == null }?.startGameTime
    }

    private fun AnimationsComponent.fadeOutClip(
        gameTime: Long,
        animation: String,
        duration: Float,
    ): AnimationsComponent {
        if (duration <= 0f) return withoutClip(animation)
        val removeAt = gameTime + duration.toDouble().secondsToTicksCeil()
        var changed = false
        val updatedClips = clips.map { clip ->
            if (clip.animation != animation || clip.stopAtGameTime != null) return@map clip
            changed = true
            clip.copy(
                fadeOut = duration,
                stopAtGameTime = gameTime,
                removeOnEnd = false,
                removeAtGameTime = removeAt,
            )
        }
        return if (changed) copy(clips = updatedClips) else this
    }

    private fun completionGameTime(
        gameTime: Long,
        model: String?,
        animation: String,
        playMode: AnimationPlayMode,
        fadeOut: Float,
    ): Long? {
        if (playMode != AnimationPlayMode.Once || model == null) return null
        val duration = ServerModelAnimationMetadata.animationDuration(model, animation) ?: return null
        return gameTime + (duration + fadeOut.coerceAtLeast(0f)).toDouble().secondsToTicksCeil()
    }

    private fun Double.secondsToTicksCeil(): Long =
        ceil(this * TICKS_PER_SECOND).toLong().coerceAtLeast(1L)

    private const val DEFAULT_FADE_DURATION = 0.33f
    private const val TICKS_PER_SECOND = 20.0
}
