package ru.hollowhorizon.hollowengine.common.addons.project.remote

import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.common.coroutines.ServerRuntimeState
import ru.hollowhorizon.hollowengine.common.network.IpAllowlist
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest

internal fun ServerPlayer.canAccessRemoteProject(): Boolean =
    ServerRuntimeState.contextOrNull(server)?.remoteProject?.hasAccess(this) == true

internal fun ServerPlayer.isConnectedPlayer(): Boolean =
    !hasDisconnected() && server.playerList.getPlayer(uuid) === this

internal fun ServerPlayer.remoteProjectPolicy(): RemoteProjectPolicy? {
    if (!server.isDedicatedServer || !isConnectedPlayer()) return null
    return RemoteProjectConfig.policy?.takeIf { it.permits((connection.remoteAddress as? InetSocketAddress)?.address) }
}

internal class RemoteProjectPolicy(token: String, private val allowlist: List<String>) {
    private val secret = token.toByteArray()
    private val ranges = IpAllowlist(allowlist)

    init {
        require(token.isEmpty() || token.isRemoteProjectToken())
    }

    fun permits(peer: InetAddress?): Boolean =
        peer != null && ranges.allows(peer.hostAddress)

    fun accepts(candidate: String): Boolean =
        secret.isNotEmpty() && candidate.isRemoteProjectToken() && MessageDigest.isEqual(secret, candidate.toByteArray())

    fun sameSettings(other: RemoteProjectPolicy): Boolean =
        MessageDigest.isEqual(secret, other.secret) && allowlist == other.allowlist
}

internal const val RemoteProjectTokenLimit = 512
private val bearerToken = Regex("[a-zA-Z0-9._~+/-]+=*")

internal fun String.isRemoteProjectToken(): Boolean = length in 1..RemoteProjectTokenLimit && bearerToken.matches(this)
