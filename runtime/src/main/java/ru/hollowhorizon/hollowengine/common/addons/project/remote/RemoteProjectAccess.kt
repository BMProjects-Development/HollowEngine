package ru.hollowhorizon.hollowengine.common.addons.project.remote

import com.google.common.net.InetAddresses
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.common.utils.isProduction
import java.net.InetSocketAddress
import java.util.UUID

internal fun ServerPlayer.canAccessRemoteProject(): Boolean =
    server.isDedicatedServer && RemoteProjectConfig.enabled && isConnectedPlayer() &&
        (server.usesAuthentication() || isLocalDevelopmentConnection()) &&
        RemoteProjectConfig.editors.allowsRemoteProjectEditor(uuid)

internal fun ServerPlayer.isConnectedPlayer(): Boolean =
    !hasDisconnected() && server.playerList.getPlayer(uuid) === this

internal fun List<String>.allowsRemoteProjectEditor(uuid: UUID): Boolean =
    any { it.equals(uuid.toString(), ignoreCase = true) }

/** Offline UUIDs are suitable only for local development with a loopback-only listener. */
private fun ServerPlayer.isLocalDevelopmentConnection(): Boolean {
    if (isProduction) return false
    val peer = connection.remoteAddress as? InetSocketAddress ?: return false
    val bind = server.localIp
    return peer.address?.isLoopbackAddress == true && InetAddresses.isInetAddress(bind) &&
        InetAddresses.forString(bind).isLoopbackAddress
}
