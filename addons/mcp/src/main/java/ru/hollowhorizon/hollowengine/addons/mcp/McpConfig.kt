package ru.hollowhorizon.hollowengine.addons.mcp

import com.google.common.net.InetAddresses
import kotlinx.serialization.*
import net.peanuuutz.tomlkt.*
import ru.hollowhorizon.hollowengine.common.config.Config
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.*
import kotlin.io.path.readText
import kotlin.io.path.writeText

@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class McpConfig(
    @TomlComment("Starts the MCP server. Off by default on dedicated servers")
    val enabled: Boolean? = null,

    @EncodeDefault
    @TomlComment("Port shared by the configured listeners. Use 0 to choose a free port per listener")
    val port: Int = 25590,

    @EncodeDefault
    @TomlComment("Local addresses to bind to. Expose MCP to the network at your own risk. Avoid production use")
    val hosts: List<String> = listOf("127.0.0.1"),

    @EncodeDefault
    @TomlComment("Allowed source IP addresses or CIDR ranges. An empty list allows any IP with a valid token")
    val allowlist: List<String> = emptyList(),

    @EncodeDefault
    @TomlComment("Agents send this as a Bearer token. Clear it and reload to generate a new one")
    val token: String = "",
)

private val configFormat = Toml {
    ignoreUnknownKeys = true
}

internal data class McpBinding(
    val hosts: Set<InetAddress>,
    val port: Int,
    val httpHosts: Set<String> = allowedHttpHosts(hosts),
)

internal data class McpSettings(val binding: McpBinding, val access: McpAccess)

internal fun loadMcpSettings(
    defaultEnabled: Boolean,
    createIfMissing: Boolean,
    path: Path = Config.CONFIG_DIR.resolve("hollowengine-mcp.toml"),
): McpSettings? {
    val content = try {
        path.readText()
    } catch (error: NoSuchFileException) {
        if (!createIfMissing) throw error
        null
    }
    val table: TomlTable
    val config = try {
        table = content?.let(configFormat::parseToTomlTable) ?: TomlTable.Empty
        configFormat.decodeFromTomlElement<McpConfig>(table)
    } catch (_: SerializationException) {
        // Parser errors can contain parts of the input, including the token.
        throw IllegalArgumentException("Invalid TOML or invalid setting types in hollowengine-mcp.toml")
    }
    val enabled = config.enabled ?: defaultEnabled
    if (!enabled) {
        if (content == null) saveMcpConfig(path, table, config.copy(enabled = false))
        return null
    }

    require(config.port in 0..65535) { "MCP port must be between 0 and 65535" }
    require(config.hosts.isNotEmpty() && config.hosts.none(String::isBlank)) {
        "MCP hosts must contain at least one non-empty bind address"
    }
    val configuredHosts = config.hosts.map(String::trim)
    val hosts = configuredHosts.flatMap { InetAddress.getAllByName(it).asList() }.toSet()
    val httpHosts = allowedHttpHosts(hosts) + configuredHosts.map { host ->
        if (':' in host && !host.startsWith('[')) "[$host]" else host
    }
    val allowlist = IpAllowlist(config.allowlist)
    val token = config.token.ifBlank {
        val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).also { generated ->
            saveMcpConfig(path, table, config.copy(enabled = true, token = generated))
        }
    }
    return McpSettings(McpBinding(hosts, config.port, httpHosts), McpAccess(token, allowlist))
}

private fun allowedHttpHosts(addresses: Set<InetAddress>): Set<String> = buildSet {
    val interfaces = if (addresses.any(InetAddress::isAnyLocalAddress)) {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.inetAddresses.toList() }
    } else {
        emptyList()
    }
    val reachable = addresses + interfaces
    reachable.forEach { address ->
        add(InetAddresses.toUriString(address))
        if (address is Inet6Address) add("[${address.hostAddress}]")
    }
    if (reachable.any(InetAddress::isLoopbackAddress)) add("localhost")
}

private fun saveMcpConfig(path: Path, previous: TomlTable, config: McpConfig) {
    val known = configFormat.encodeToTomlElement(config) as TomlTable
    val updated = TomlTable(previous.content + known.content).annotated(previous.annotations + known.annotations)
    val directory = path.toAbsolutePath().parent
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, path.fileName.toString(), ".tmp")
    try {
        temporary.writeText(configFormat.encodeToString(updated))
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
