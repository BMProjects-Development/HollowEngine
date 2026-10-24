package ru.hollowhorizon.hollowengine.client.discord

import net.minecraft.Util
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.client.utils.langOrDefault
import ru.hollowhorizon.hollowengine.common.config.DiscordPresenceConfig

private const val EngineApplicationId = "1563554195993526342"
private const val EngineRepositoryUrl = "https://github.com/HollowHorizon/HollowEngine"

object EngineDiscordPresenceProvider : DiscordPresenceProvider {
    override fun presence(context: DiscordPresenceContext): DiscordPresence {
        val mode = when {
            context.editorOpen -> text("editor", "Working in IDE")
            !context.inWorld -> text("details", "Using HollowEngine")
            context.singleplayer -> text("singleplayer", "Playing singleplayer")
            else -> text("multiplayer", "Playing multiplayer")
        }
        val details = if (context.inWorld && context.paused) text("paused", "%s (paused)", mode) else mode
        val state = when {
            context.inWorld -> worldLocation(context)
            context.loading -> text("loading", "Loading Minecraft")
            else -> text("menu", "In the main menu")
        }
        return DiscordPresence(
            DiscordPresenceConfig.applicationId ?: EngineApplicationId, DiscordActivity(
                details = details.fitDiscordText(128),
                state = state.fitDiscordText(128),
                startTimestamp = context.startedAtSeconds,
                buttons = listOf(DiscordButton(text("github", "GitHub").fitDiscordText(32), EngineRepositoryUrl)),
            )
        )
    }

    private fun worldLocation(context: DiscordPresenceContext): String {
        val dimension = context.dimension?.let(::dimensionName) ?: return text("in_world", "Exploring the world")
        val biome = context.biome?.let { Util.makeDescriptionId("biome", it).langOrDefault(it.toString()) }
        return if (biome == null) text("dimension", "In %s", dimension)
        else text("location", "%s (%s)", dimension, biome)
    }

    private fun dimensionName(id: ResourceLocation): String {
        val fallback = when (id) {
            Level.OVERWORLD.location() -> text("overworld", "Overworld")
            Level.NETHER.location() -> text("nether", "The Nether")
            Level.END.location() -> text("end", "The End")
            else -> id.toString()
        }
        return Util.makeDescriptionId("dimension", id).langOrDefault(fallback)
    }

    private fun text(key: String, fallback: String, vararg args: Any): String =
        "hollowengine.gui.discord.$key".langOrDefault(fallback, *args)

    private fun String.fitDiscordText(limit: Int): String =
        if (codePointCount(0, length) <= limit) this else substring(0, offsetByCodePoints(0, limit - 1)) + "…"
}
