package ru.hollowhorizon.hollowengine.common.addons.project

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.SharedConstants
import net.minecraft.server.packs.PackType
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEnvironment

/**
 * Files that let an addon jar sit in `mods`: a descriptor for each loader, which both read only
 * their own of, and a `pack.mcmeta` for its assets and data.
 */
internal object HollowModMetadata {
    const val FABRIC_DESCRIPTOR = "fabric.mod.json"
    const val NEOFORGE_DESCRIPTOR = "META-INF/neoforge.mods.toml"
    const val PACK_METADATA = "pack.mcmeta"

    private const val DEFAULT_LICENSE = "All Rights Reserved"
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun files(properties: ProjectProperties): Map<String, String> = mapOf(
        FABRIC_DESCRIPTOR to fabric(properties),
        NEOFORGE_DESCRIPTOR to neoforge(properties),
        PACK_METADATA to pack(properties),
    )

    /** A loader mod id for [addonId], which may contain characters loaders do not allow. */
    fun modId(addonId: String): String {
        val folded = addonId.lowercase().map { character ->
            if (character.isLetterOrDigit() || character == '_') character else '_'
        }.joinToString("")
        return if (folded.firstOrNull()?.isLetter() == true) folded else "a$folded"
    }

    private fun fabric(properties: ProjectProperties): String {
        val json = JsonObject().apply {
            addProperty("schemaVersion", 1)
            addProperty("id", modId(properties.id))
            addProperty("version", properties.version)
            addProperty("name", properties.displayName)
            addProperty("description", properties.description)
            add("authors", JsonArray().apply { properties.authors.forEach(::add) })
            addProperty("license", properties.license.ifBlank { DEFAULT_LICENSE })
            if (properties.icon.isNotBlank()) addProperty("icon", properties.icon)
            addProperty(
                "environment",
                when (properties.environment) {
                    HollowAddonEnvironment.CLIENT -> "client"
                    HollowAddonEnvironment.SERVER -> "server"
                    HollowAddonEnvironment.COMMON -> "*"
                },
            )
            add("depends", JsonObject().apply { addProperty(HollowEngine.MODID, "*") })
        }
        return gson.toJson(json) + "\n"
    }

    private fun neoforge(properties: ProjectProperties): String = buildString {
        val modId = modId(properties.id)
        appendLine("modLoader = \"lowcodefml\"")
        appendLine("loaderVersion = \"[1,)\"")
        appendLine("license = ${tomlString(properties.license.ifBlank { DEFAULT_LICENSE })}")
        appendLine()
        appendLine("[[mods]]")
        appendLine("modId = ${tomlString(modId)}")
        appendLine("version = ${tomlString(properties.version)}")
        appendLine("displayName = ${tomlString(properties.displayName)}")
        appendLine("description = ${tomlString(properties.description)}")
        appendLine("authors = ${tomlString(properties.authors.joinToString(", "))}")
        if (properties.icon.isNotBlank()) appendLine("logoFile = ${tomlString(properties.icon)}")
        appendLine("# The addon is loaded by HollowEngine, not by the loader, so a side that only has it")
        appendLine("# installed must not be kicked over a mod list mismatch.")
        appendLine("displayTest = \"IGNORE_ALL_VERSION\"")
        appendLine()
        appendLine("[[dependencies.$modId]]")
        appendLine("modId = ${tomlString(HollowEngine.MODID)}")
        appendLine("type = \"required\"")
        appendLine("versionRange = \"[1,)\"")
        appendLine("ordering = \"NONE\"")
        appendLine("side = \"BOTH\"")
    }

    private fun pack(properties: ProjectProperties): String {
        val version = SharedConstants.getCurrentVersion()
        val resources = version.getPackVersion(PackType.CLIENT_RESOURCES)
        val data = version.getPackVersion(PackType.SERVER_DATA)
        val json = JsonObject().apply {
            add("pack", JsonObject().apply {
                addProperty("description", properties.displayName)
                addProperty("pack_format", resources)
                add("supported_formats", JsonArray().apply {
                    add(minOf(resources, data))
                    add(maxOf(resources, data))
                })
            })
        }
        return gson.toJson(json) + "\n"
    }

    private fun tomlString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character == '\n' -> append("\\n")
                character == '\r' -> append("\\r")
                character == '\t' -> append("\\t")
                character < ' ' -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
        append('"')
    }
}
