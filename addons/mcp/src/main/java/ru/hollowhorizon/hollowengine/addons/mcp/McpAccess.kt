package ru.hollowhorizon.hollowengine.addons.mcp

import ru.hollowhorizon.hollowengine.common.network.IpAllowlist
import java.security.MessageDigest

internal class McpAccess(val token: String, val allowlist: IpAllowlist) {
    private val authorization = "Bearer $token".toByteArray()

    fun acceptsToken(header: String?): Boolean =
        header != null && MessageDigest.isEqual(authorization, header.toByteArray())
}
