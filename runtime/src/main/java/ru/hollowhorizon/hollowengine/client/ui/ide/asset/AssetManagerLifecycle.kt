package ru.hollowhorizon.hollowengine.client.ui.ide.asset

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import ru.hollowhorizon.hollowengine.api.ReloadListener
import ru.hollowhorizon.hollowengine.common.utils.PlayerPermissions
import ru.hollowhorizon.hollowengine.common.utils.Side
import java.util.concurrent.atomic.AtomicInteger

/**
 * Converts Minecraft's non-observable resource/session fields into narrow Compose revisions.
 * Observing is cheap and does not invalidate the UI until one of the relevant identities changes.
 */
@ReloadListener(Side.CLIENT)
internal object AssetManagerLifecycle : ResourceManagerReloadListener {
    private val completedClientReloads = AtomicInteger()

    private var observedClientManager: ResourceManager? = null
    private var observedServerManager: ResourceManager? = null
    private var observedConnection: ClientPacketListener? = null
    private var observedOperator = false
    private var observedClientReloads = -1
    private var initialized = false

    var clientRevision by mutableIntStateOf(0)
        private set
    var serverRevision by mutableIntStateOf(0)
        private set

    var operator by mutableStateOf(false)
        private set

    override fun onResourceManagerReload(resourceManager: ResourceManager) {
        completedClientReloads.incrementAndGet()
    }

    fun observe(minecraft: Minecraft) {
        val clientManager = minecraft.resourceManager
        val serverManager = minecraft.singleplayerServer?.resourceManager
        val connection = minecraft.connection
        val operator = minecraft.player?.hasPermissions(PlayerPermissions.GAMEMASTER) == true
        val clientReloads = completedClientReloads.get()

        if (!initialized || clientManager !== observedClientManager || clientReloads != observedClientReloads) {
            observedClientManager = clientManager
            observedClientReloads = clientReloads
            clientRevision++
        }
        if (!initialized || serverManager !== observedServerManager || connection !== observedConnection || operator != observedOperator) {
            observedServerManager = serverManager
            observedConnection = connection
            observedOperator = operator
            this.operator = operator
            serverRevision++
        }
        initialized = true
    }
}
