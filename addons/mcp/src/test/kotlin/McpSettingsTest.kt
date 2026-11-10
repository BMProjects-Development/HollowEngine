import ru.hollowhorizon.hollowengine.addons.mcp.IpAllowlist
import ru.hollowhorizon.hollowengine.addons.mcp.loadMcpSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*

class McpSettingsTest {
    @Test
    fun `old configuration keeps loopback and the existing token`() = withConfig { path ->
        path.writeText("enabled = true\nport = 25591\ntoken = \"existing\"\n")
        val settings = assertNotNull(loadMcpSettings(false, false, path))
        assertEquals(25591, settings.binding.port)
        assertEquals("127.0.0.1", settings.binding.hosts.single().hostAddress)
        assertEquals("existing", settings.access.token)
        assertTrue(settings.access.allowlist.allows("192.0.2.5"))
    }

    @Test
    fun `a missing config gets documented defaults and a persistent token`() = withConfig { path ->
        assertNull(loadMcpSettings(false, true, path))
        assertContains(path.readText(), "at your own risk")
        assertContains(path.readText(), "allowlist = [")
        path.writeText(path.readText().replace("enabled = false", "enabled = true") + "\ncustom = 42\n")
        val token = assertNotNull(loadMcpSettings(false, false, path)).access.token
        assertTrue(token.isNotBlank())
        assertEquals(token, assertNotNull(loadMcpSettings(false, false, path)).access.token)
        assertContains(path.readText(), "custom = 42")
    }

    @Test
    fun `an invalid allowlist or setting type fails the whole load`() = withConfig { path ->
        listOf(
            "allowlist = \"192.0.2.0/24\"",
            "allowlist = [\"192.0.2.0/33\"]",
            "allowlist = [\"::/129\"]",
            "allowlist = [\"localhost\"]",
            "allowlist = [\"10.0.0.1/\"]",
            "hosts = []",
        ).forEach { invalid ->
            val content = "enabled = true\n$invalid\n"
            path.writeText(content)
            assertFailsWith<IllegalArgumentException>(invalid) { loadMcpSettings(false, false, path) }
            assertEquals(content, path.readText())
        }
    }

    @Test
    fun `exact IPs and CIDR ranges match in both families`() {
        val allowlist =
            IpAllowlist(listOf("127.0.0.1", "192.0.2.60/27", "2001:db8:1200::/41", "::ffff:198.51.100.0/120"))
        listOf("127.0.0.1", "192.0.2.63", "2001:db8:127f::1", "::ffff:192.0.2.32", "198.51.100.9").forEach {
                assertTrue(
                    allowlist.allows(it),
                    it
                )
            }
        listOf("127.0.0.2", "192.0.2.64", "2001:db8:1280::1", "198.51.101.9", "localhost").forEach {
                assertFalse(
                    allowlist.allows(it),
                    it
                )
            }
        assertTrue(IpAllowlist(listOf("0.0.0.0/0")).allows("203.0.113.1"))
        assertFalse(IpAllowlist(listOf("::/0")).allows("203.0.113.1"))
    }

    private fun withConfig(block: (Path) -> Unit) {
        val directory = createTempDirectory("mcp-config-test")
        val path = directory.resolve("hollowengine-mcp.toml")
        try {
            block(path)
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}
