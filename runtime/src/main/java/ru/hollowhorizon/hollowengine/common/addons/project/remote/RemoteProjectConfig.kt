package ru.hollowhorizon.hollowengine.common.addons.project.remote

import ru.hollowhorizon.hollowengine.common.config.Config
import ru.hollowhorizon.hollowengine.common.config.ConfigName
import ru.hollowhorizon.hollowengine.common.config.PropertyComment
import ru.hollowhorizon.hollowengine.common.config.PropertyName

@ConfigName("hollowengine/remote-project")
internal object RemoteProjectConfig : Config() {
    @PropertyComment("Allow remote project editing on an authenticated dedicated Minecraft server or a loopback-only development server.")
    val enabled by property(false)

    @PropertyComment("Minecraft player UUIDs allowed to edit the project and run server scripts. An empty list grants no access.")
    @PropertyName("editors")
    val editors by list<String>()
}
