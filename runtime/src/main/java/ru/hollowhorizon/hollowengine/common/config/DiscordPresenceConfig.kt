package ru.hollowhorizon.hollowengine.common.config

@ConfigName("hollowengine-discord")
object DiscordPresenceConfig : Config() {
    @PropertyComment("Show Discord Rich Presence on the local desktop client")
    var enabled by property(true)

    @PropertyComment("Optional Discord Application ID override")
    @PropertyName("application_id")
    var applicationId by nullableProperty<String>()
}
