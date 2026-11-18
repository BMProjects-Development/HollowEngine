package ru.hollowhorizon.hollowengine.common.addons.project.remote

import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.TomlTable
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.config.Config
import ru.hollowhorizon.hollowengine.common.config.ConfigName
import ru.hollowhorizon.hollowengine.common.config.PropertyComment
import ru.hollowhorizon.hollowengine.common.utils.toml.toml
import java.nio.file.Path

@ConfigName("hollowengine/remote-project")
internal object RemoteProjectConfig : Config() {
    @PropertyComment("Allow remote project editing on this dedicated server.")
    private val enabled by property(false)

    @PropertyComment("Secret bearer token for editing project files and running server scripts. An empty token disables access.")
    private val token by property("")

    @PropertyComment("Peer IP addresses and CIDR ranges allowed to authenticate. An empty list allows any peer.")
    private val allowlist by list<String>()

    @Volatile
    var policy: RemoteProjectPolicy? = null
        private set

    override fun applyConfig(table: TomlTable) {
        val settings = toml.decodeFromTomlElement(Settings.serializer(), table)
        val next = RemoteProjectPolicy(settings.token, settings.allowlist)
            .takeIf { settings.enabled && settings.token.isNotEmpty() }
        super.applyConfig(table)
        policy = policy?.takeIf { next != null && it.sameSettings(next) } ?: next
    }

    override fun handleLoadFailure(fileName: Path, error: Exception) {
        policy = null
        HollowEngine.LOGGER.warn("Could not load {}. Remote project access disabled.", fileName)
    }

    override fun onConfigRemoved() {
        policy = null
    }

    @Serializable
    private class Settings(val enabled: Boolean = false, val token: String = "", val allowlist: List<String> = emptyList())
}
