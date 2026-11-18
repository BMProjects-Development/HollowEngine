package ru.hollowhorizon.hollowengine.common.addons.project.remote

import com.google.common.net.InetAddresses
import kotlin.test.*

class RemoteProjectPolicyTest {
    @Test
    fun `token comparison is exact and rejects empty or oversized input`() {
        val policy = RemoteProjectPolicy("Secret_+==", emptyList())
        assertTrue(policy.accepts("Secret_+=="))
        assertFalse(policy.accepts("secret_+=="))
        assertFalse(policy.accepts("Secret_+== "))
        assertFalse(policy.accepts(""))
        assertFalse(policy.accepts("a".repeat(RemoteProjectTokenLimit + 1)))
        assertFalse(RemoteProjectPolicy("", emptyList()).accepts("Secret_+=="))
    }

    @Test
    fun `allowlist supports individual addresses and partial CIDR bytes`() {
        val policy = RemoteProjectPolicy("secret", listOf("127.0.0.1", "192.0.2.64/26", "2001:db8::/33"))
        for (address in listOf("127.0.0.1", "::ffff:127.0.0.1", "192.0.2.127", "2001:db8:7fff::1")) {
            assertTrue(policy.permits(InetAddresses.forString(address)), address)
        }
        for (address in listOf("127.0.0.2", "192.0.2.128", "2001:db8:8000::1", "::1")) {
            assertFalse(policy.permits(InetAddresses.forString(address)), address)
        }
        assertFalse(policy.permits(null))
        assertTrue(RemoteProjectPolicy("secret", emptyList()).permits(InetAddresses.forString("::1")))
    }

    @Test
    fun `configuration rejects invalid CIDR ranges and host names`() {
        for (entry in listOf("localhost", "127.0.0.1/33", "2001:db8::/129", "192.0.2.1/")) {
            assertFailsWith<IllegalArgumentException>(entry) { RemoteProjectPolicy("secret", listOf(entry)) }
        }
        assertFailsWith<IllegalArgumentException> { RemoteProjectPolicy("not a bearer token", emptyList()) }
    }
}
