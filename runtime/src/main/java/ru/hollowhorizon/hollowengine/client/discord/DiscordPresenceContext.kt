package ru.hollowhorizon.hollowengine.client.discord

data class DiscordPresenceContext(
    val startedAtSeconds: Long,
    val editorOpen: Boolean,
    val loading: Boolean = false,
)