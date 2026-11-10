import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeNotNull
import ru.hollowhorizon.hollowengine.addons.mcp.*
import java.io.IOException
import java.net.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.*

class McpServerTest {
    private val echo = McpTool(
        name = "echo",
        description = "Repeats its text",
        parameters = listOf(ToolParameter("text", ParameterType.STRING, "What to repeat", required = true)),
        readOnly = true,
    ) { arguments -> textResult("echo: ${arguments.string("text")}") }

    private val server = McpServer("test", listOf(echo)) { "instructions for the agent" }
    private val client = HttpClient.newHttpClient()
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val settings = McpSettings(McpBinding(setOf(loopback), 0), McpAccess(TOKEN, IpAllowlist(emptyList())))

    @BeforeTest
    fun start() = runBlocking { server.reconfigure(settings) }

    @AfterTest
    fun stop() = runBlocking { server.reconfigure(null) }

    @Test
    fun `a request without the token is refused`() {
        assertEquals(401, post(INITIALIZE, token = null).statusCode())
        assertEquals(401, post(INITIALIZE, token = "wrong").statusCode())
    }

    @Test
    fun `a hostile browser origin is refused even with a valid token`() {
        assertEquals(403, post(INITIALIZE, origin = "https://example.invalid").statusCode())
    }

    @Test
    fun `a new allowlist checks the socket IP on existing sessions`() = runBlocking {
        val initialized = post(INITIALIZE)
        val session = assertNotNull(initialized.headers().firstValue("mcp-session-id").orElse(null))
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", session = session)
        val url = server.urls.single()
        server.reconfigure(settings.copy(access = McpAccess(TOKEN, IpAllowlist(listOf("192.0.2.0/24")))))
        val listTools = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
        assertEquals(403, post(listTools, session = session, forwardedFor = "192.0.2.1").statusCode())

        val newToken = "new-token"
        server.reconfigure(settings.copy(access = McpAccess(newToken, IpAllowlist(listOf("127.0.0.0/8")))))
        assertEquals(url, server.urls.single())
        assertEquals(newToken, server.connection(AgentClient.TOKEN))
        assertEquals(401, post(listTools, session = session).statusCode())
        assertContains(post(listTools, token = newToken, session = session).body(), "\"echo\"")
    }

    @Test
    fun `each configured address accepts requests`() = runBlocking {
        server.reconfigure(settings.copy(binding = McpBinding(setOf(loopback, InetAddress.getByName("::1")), 0)))
        assertEquals(2, server.urls.size)
        server.urls.forEach { url ->
            val response = post(INITIALIZE, url = url)
            assertEquals(200, response.statusCode(), "$url: ${response.body()}")
        }
    }

    @Test
    fun `a wildcard listener accepts an address on a local network interface`() = runBlocking {
        val networkAddress = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        assumeNotNull(networkAddress)
        val wildcard = InetAddress.getByName("0.0.0.0")
        server.reconfigure(settings.copy(binding = McpBinding(setOf(wildcard), 0)))
        val port = URI(server.urls.single()).port
        val url = "http://${requireNotNull(networkAddress).hostAddress}:$port/mcp"
        val response = post(INITIALIZE, url = url)
        assertEquals(200, response.statusCode(), response.body())
        assertContains(assertNotNull(server.connection(AgentClient.CODEX)), "http://127.0.0.1:$port/mcp")
    }

    @Test
    fun `a failed rebind restores the previous access policy`() = runBlocking {
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        server.reconfigure(settings.copy(binding = McpBinding(setOf(loopback), port)))
        val url = server.urls.single()
        assertEquals(200, post(INITIALIZE).statusCode())

        val ipv6 = InetAddress.getByName("::1")
        ServerSocket(port, 1, ipv6).use {
            val conflicting = McpBinding(setOf(loopback, ipv6), port)
            val error = assertFailsWith<IOException> {
                server.reconfigure(McpSettings(conflicting, McpAccess("other", IpAllowlist(emptyList()))))
            }
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is BindException })
        }
        assertEquals(url, server.urls.single())
        assertEquals(200, post(INITIALIZE).statusCode())
        assertEquals(401, post(INITIALIZE, token = "other").statusCode())
    }

    @Test
    fun `a listener can change addresses on the same port`() = runBlocking {
        val port = URI(server.urls.single()).port
        assertEquals(200, post(INITIALIZE).statusCode())
        val wildcard = InetAddress.getByName("0.0.0.0")
        server.reconfigure(settings.copy(binding = McpBinding(setOf(wildcard), port)))
        assertEquals(port, URI(server.urls.single()).port)
        assertEquals(200, post(INITIALIZE, url = "http://127.0.0.1:$port/mcp").statusCode())
    }

    @Test
    fun `a session lists and calls the tools`() {
        val initialized = post(INITIALIZE)
        assertEquals(200, initialized.statusCode(), initialized.body())
        assertContains(initialized.body(), "instructions for the agent")
        val session = assertNotNull(initialized.headers().firstValue("mcp-session-id").orElse(null))

        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", session = session)
        val listed = post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""", session = session).body()
        assertContains(listed, "\"echo\"")
        assertContains(listed, "\"readOnlyHint\":true")

        val called = post(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""",
            session = session,
        ).body()
        assertContains(called, "echo: hi")

        val missing = post(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"echo","arguments":{}}}""",
            session = session,
        ).body()
        assertContains(missing, "'text' is required")
        assertContains(missing, "\"isError\":true")
    }

    private fun post(
        body: String,
        token: String? = TOKEN,
        session: String? = null,
        forwardedFor: String? = null,
        origin: String? = null,
        url: String = server.urls.single(),
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .apply { if (session != null) header("mcp-session-id", session) }
            .apply { if (forwardedFor != null) header("X-Forwarded-For", forwardedFor) }
            .apply { if (origin != null) header("Origin", origin) }.POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        const val TOKEN = "secret"
        const val INITIALIZE =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
    }
}
