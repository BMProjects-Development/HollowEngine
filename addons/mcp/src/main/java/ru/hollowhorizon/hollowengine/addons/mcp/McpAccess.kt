package ru.hollowhorizon.hollowengine.addons.mcp

import com.google.common.net.InetAddresses
import java.security.MessageDigest

internal class McpAccess(val token: String, val allowlist: IpAllowlist) {
    private val authorization = "Bearer $token".toByteArray()

    fun acceptsToken(header: String?): Boolean =
        header != null && MessageDigest.isEqual(authorization, header.toByteArray())
}

internal class IpAllowlist(entries: List<String>) {
    private val prefixes = entries.map(IpPrefix::parse)

    val isEmpty: Boolean get() = prefixes.isEmpty()

    fun allows(address: String): Boolean {
        if (isEmpty) return true
        val literal = address.substringBefore('%')
        if (!InetAddresses.isInetAddress(literal)) return false
        val bytes = InetAddresses.forString(literal).address
        return prefixes.any { it.matches(bytes) }
    }
}

private class IpPrefix(private val address: ByteArray, private val length: Int) {
    fun matches(peer: ByteArray): Boolean {
        if (address.size != peer.size) return false
        val fullBytes = length / Byte.SIZE_BITS
        for (index in 0 until fullBytes) {
            if (address[index] != peer[index]) return false
        }
        val remaining = length % Byte.SIZE_BITS
        if (remaining == 0) return true
        val mask = 0xff shl (Byte.SIZE_BITS - remaining)
        return ((address[fullBytes].toInt() xor peer[fullBytes].toInt()) and mask) == 0
    }

    companion object {
        private const val IPV6_BITS = 128
        private const val MAPPED_PREFIX_BITS = 96

        fun parse(entry: String): IpPrefix {
            val value = entry.trim()
            val literal = value.substringBefore('/')
            require('%' !in literal && InetAddresses.isInetAddress(literal)) { "Invalid MCP allowlist entry: $entry" }
            val bytes = InetAddresses.forString(literal).address
            val ipv6 = ':' in literal
            val maxLength = if (ipv6) IPV6_BITS else bytes.size * Byte.SIZE_BITS
            val suffix = value.substringAfter('/', "")
            val length = if ('/' in value) suffix.toIntOrNull() else maxLength
            require(length != null && length in 0..maxLength && suffix.all { it in '0'..'9' }) {
                "Invalid MCP allowlist entry: $entry"
            }
            if (!ipv6 || bytes.size * Byte.SIZE_BITS == IPV6_BITS) return IpPrefix(bytes, length)

            // The JVM represents IPv4-mapped peer addresses as IPv4. Normalize mapped subnets likewise.
            if (length >= MAPPED_PREFIX_BITS) return IpPrefix(bytes, length - MAPPED_PREFIX_BITS)

            // A broader prefix is an IPv6 network. Preserve its IPv6 bits for matching native peers.
            val mapped = ByteArray(IPV6_BITS / Byte.SIZE_BITS)
            val ipv4Offset = MAPPED_PREFIX_BITS / Byte.SIZE_BITS
            mapped[ipv4Offset - 2] = -1
            mapped[ipv4Offset - 1] = -1
            bytes.copyInto(mapped, destinationOffset = ipv4Offset)
            return IpPrefix(mapped, length)
        }
    }
}
