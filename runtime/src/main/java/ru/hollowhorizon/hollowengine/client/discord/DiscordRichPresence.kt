package ru.hollowhorizon.hollowengine.client.discord

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.LOGGER
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOverlay
import ru.hollowhorizon.hollowengine.common.config.DiscordPresenceConfig
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val SnapshotInterval = 1.seconds

data class DiscordPresenceContext(
    val startedAtSeconds: Long,
    val inWorld: Boolean,
    val singleplayer: Boolean,
    val editorOpen: Boolean,
    val dimension: ResourceLocation? = null,
    val biome: ResourceLocation? = null,
    val paused: Boolean = false,
    val loading: Boolean = false,
)

/** Runs on the Minecraft client thread. Returning null disconnects and hides presence. */
fun interface DiscordPresenceProvider {
    fun presence(context: DiscordPresenceContext): DiscordPresence?
}

/** Engine branding by default; projects can replace the provider without touching the IPC layer. */
@ClientOnly
object DiscordRichPresence {
    var provider: DiscordPresenceProvider = EngineDiscordPresenceProvider

    private var client: DiscordRpcClient? = null
    private var startedAtSeconds = 0L
    private var lastSampleNanos: Long? = null
    private var lastProviderError: String? = null

    internal fun start() {
        if (client != null) return
        startedAtSeconds = Instant.now().epochSecond
        client = DiscordRpcClient(onError = { LOGGER.debug("Discord Rich Presence connection failed", it) })
    }

    internal fun tick(minecraft: Minecraft) {
        val rpc = client ?: return
        val now = System.nanoTime()
        if (lastSampleNanos?.let { now - it < SnapshotInterval.inWholeNanoseconds } == true) return
        lastSampleNanos = now
        if (!DiscordPresenceConfig.enabled) {
            rpc.update(null)
            return
        }
        try {
            val level = minecraft.level
            val player = minecraft.player?.takeIf { it.level() === level }
            rpc.update(
                provider.presence(
                    DiscordPresenceContext(
                        startedAtSeconds = startedAtSeconds,
                        inWorld = level != null,
                        singleplayer = minecraft.hasSingleplayerServer(),
                        editorOpen = HollowIdeOverlay.isExpanded(),
                        dimension = level?.dimension()?.location(),
                        biome = player?.let {
                            level?.getBiome(it.blockPosition())?.unwrapKey()?.orElse(null)?.location()
                        },
                        paused = minecraft.isPaused,
                        loading = level == null && minecraft.overlay != null,
                    )
                )
            )
            lastProviderError = null
        } catch (exception: Exception) {
            rpc.update(null)
            val error = "${exception.javaClass.name}: ${exception.message}"
            if (lastProviderError != error) {
                LOGGER.warn(
                    "Cannot create Discord Rich Presence; check the provider and hollowengine-discord.toml",
                    exception
                )
                lastProviderError = error
            }
        }
    }

    internal fun stop() {
        client?.close()
        client = null
        lastSampleNanos = null
        lastProviderError = null
    }
}
