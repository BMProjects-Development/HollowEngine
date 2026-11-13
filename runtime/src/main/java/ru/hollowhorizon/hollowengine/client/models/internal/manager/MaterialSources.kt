package ru.hollowhorizon.hollowengine.client.models.internal.manager

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.client.resources.PlayerSkin
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.entity.SkullBlockEntity
import ru.hollowhorizon.hollowengine.common.attachments.components.PlayerArms
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.models.MaterialSource
import ru.hollowhorizon.hollowengine.common.models.PlayerSkinPart
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/** A material source turned into something the renderer can bind. */
data class ResolvedMaterial(
    val texture: ResourceLocation?,
    val normal: ResourceLocation? = null,
    val specular: ResourceLocation? = null,
    val color: String? = null,
)

/**
 * Turns a [MaterialSource] into textures.
 */
@ClientOnly
object MaterialSources {
    private val lookups = ConcurrentHashMap<String, CompletableFuture<PlayerSkin?>>()

    /**
     * Resolves any [source] as Material.
     */
    fun resolve(source: MaterialSource, onLoaded: () -> Unit = {}): ResolvedMaterial = when (source) {
        is MaterialSource.Texture -> ResolvedMaterial(
            texture = source.texture,
            normal = source.normal,
            specular = source.specular,
            color = source.color,
        )

        is MaterialSource.Player -> skinOf(source.player, onLoaded).let { skin ->
            ResolvedMaterial(
                texture = when (source.part) {
                    PlayerSkinPart.SKIN -> skin.texture()
                    PlayerSkinPart.CAPE -> skin.capeTexture()
                    PlayerSkinPart.ELYTRA -> skin.elytraTexture()
                },
            )
        }
    }

    /** The arm shape [source] implies, for a skin that came off a real player, and null otherwise. */
    fun armsOf(source: MaterialSource): PlayerArms? = when (source) {
        is MaterialSource.Texture -> null
        is MaterialSource.Player -> when (skinOf(source.player) {}.model()) {
            PlayerSkin.Model.SLIM -> PlayerArms.SLIM
            PlayerSkin.Model.WIDE -> PlayerArms.WIDE
        }
    }

    /**
     * The skin of a player named by nickname or uuid.
     *
     * Someone on this server is known already: the client got their skin with the player list.
     * Anyone else loading using profile lookup.
     */
    private fun skinOf(player: String, onLoaded: () -> Unit): PlayerSkin {
        val uuid = player.asUuid()
        val connection = Minecraft.getInstance().connection
        val online = uuid?.let { connection?.getPlayerInfo(it) } ?: connection?.getPlayerInfo(player)
        if (online != null) {
            val loading = Minecraft.getInstance().skinManager.getOrLoad(online.profile)
            if (!loading.isDone) loading.thenRun { RenderSystem.recordRenderCall(onLoaded) }
            return online.skin
        }

        val lookup = lookups.computeIfAbsent(player) { lookUp(it, uuid) }
        if (!lookup.isDone) lookup.thenRun { RenderSystem.recordRenderCall(onLoaded) }
        return lookup.getNow(null) ?: DefaultPlayerSkin.get(uuid ?: offlineUuid(player))
    }

    private fun lookUp(player: String, uuid: UUID?): CompletableFuture<PlayerSkin?> {
        val profile = uuid?.let { SkullBlockEntity.fetchGameProfile(it) } ?: SkullBlockEntity.fetchGameProfile(player)

        return profile.thenCompose { found ->
            found.map(Minecraft.getInstance().skinManager::getOrLoad)
                .orElseGet { CompletableFuture.completedFuture(null) }
        }.exceptionally { null }
    }

    private fun String.asUuid(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

    private fun offlineUuid(name: String): UUID =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
}
