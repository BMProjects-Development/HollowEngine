import groovy.json.JsonOutput
import java.util.Properties

val addonModMetadataDir = layout.buildDirectory.dir("hollowengine/mod-metadata")

fun addonDescriptorProperty(name: String): String? {
    val descriptor = projectDir.resolve("src/main/resources/META-INF/plugin.properties")
    if (!descriptor.isFile) return null

    val properties = Properties()
    descriptor.inputStream().use(properties::load)
    return properties.getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
}

fun addonModId(addonId: String): String {
    val folded = addonId.lowercase().map { character ->
        if (character.isLetterOrDigit() || character == '_') character else '_'
    }.joinToString("")
    return if (folded.firstOrNull()?.isLetter() == true) folded else "a$folded"
}

fun tomlString(value: String): String = buildString {
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

// Kept in step with the metadata the in-game project export writes, see HollowModMetadata.
val generateAddonModMetadata = tasks.register("generateAddonModMetadata") {
    group = "build"
    description = "Generates mod metadata that let this addon live in the mods folder."

    val engineModId = rootProject.property("modId") as String
    val addonId = addonDescriptorProperty("id") ?: project.name
    val modId = addonModId(addonId)
    val displayName = addonDescriptorProperty("name") ?: addonId
    val addonVersion = version.toString()
    val license = addonDescriptorProperty("license") ?: rootProject.property("license") as String
    val addonDescription = addonDescriptorProperty("description").orEmpty()
    val authors = addonDescriptorProperty("authors")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
        ?: listOf(rootProject.property("modAuthor") as String)
    val icon = addonDescriptorProperty("icon")
    val environment = addonDescriptorProperty("environment")?.lowercase() ?: "common"
    val fabricEnvironment = when (environment) {
        "client" -> "client"
        "server" -> "server"
        else -> "*"
    }
    val resourcePackFormat = (rootProject.property("resourcePackFormat") as String).toInt()
    val dataPackFormat = (rootProject.property("dataPackFormat") as String).toInt()

    inputs.property("modId", modId)
    inputs.property("version", addonVersion)
    inputs.property("name", displayName)
    inputs.property("description", addonDescription)
    inputs.property("authors", authors)
    inputs.property("icon", icon.orEmpty())
    inputs.property("environment", environment)
    inputs.property("engineModId", engineModId)
    inputs.property("license", license)
    inputs.property("resourcePackFormat", resourcePackFormat)
    inputs.property("dataPackFormat", dataPackFormat)
    outputs.dir(addonModMetadataDir)

    doLast {
        val root = addonModMetadataDir.get().asFile
        root.deleteRecursively()
        root.resolve("META-INF").mkdirs()

        val fabric = linkedMapOf<String, Any>(
            "schemaVersion" to 1,
            "id" to modId,
            "version" to addonVersion,
            "name" to displayName,
            "description" to addonDescription,
            "authors" to authors,
            "license" to license,
            "environment" to fabricEnvironment,
            "depends" to mapOf(engineModId to "*"),
        )
        if (icon != null) fabric["icon"] = icon
        root.resolve("fabric.mod.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(fabric)) + "\n")

        root.resolve("META-INF/neoforge.mods.toml").writeText(
            buildString {
                appendLine("modLoader = \"lowcodefml\"")
                appendLine("loaderVersion = \"[1,)\"")
                appendLine("license = ${tomlString(license)}")
                appendLine()
                appendLine("# The Fabric descriptor lives in the same jar; this keeps Sinytra Connector from loading it a second time.")
                appendLine("[properties]")
                appendLine("\"connector:placeholder\" = true")
                appendLine()
                appendLine("[[mods]]")
                appendLine("modId = ${tomlString(modId)}")
                appendLine("version = ${tomlString(addonVersion)}")
                appendLine("displayName = ${tomlString(displayName)}")
                appendLine("description = ${tomlString(addonDescription)}")
                appendLine("authors = ${tomlString(authors.joinToString(", "))}")
                if (icon != null) appendLine("logoFile = ${tomlString(icon)}")
                appendLine("# The addon is loaded by HollowEngine, not by the loader, so a side that only has it")
                appendLine("# installed must not be kicked over a mod list mismatch.")
                appendLine("displayTest = \"IGNORE_ALL_VERSION\"")
                appendLine()
                appendLine("[[dependencies.$modId]]")
                appendLine("modId = ${tomlString(engineModId)}")
                appendLine("type = \"required\"")
                appendLine("versionRange = \"[1,)\"")
                appendLine("ordering = \"NONE\"")
                appendLine("side = \"BOTH\"")
            }
        )

        // Both loaders serve a mod's assets and data only with this file in place.
        root.resolve("pack.mcmeta").writeText(
            JsonOutput.prettyPrint(
                JsonOutput.toJson(
                    mapOf(
                        "pack" to mapOf(
                            "description" to displayName,
                            "pack_format" to resourcePackFormat,
                            "supported_formats" to listOf(
                                minOf(resourcePackFormat, dataPackFormat),
                                maxOf(resourcePackFormat, dataPackFormat),
                            ),
                        ),
                    ),
                ),
            ) + "\n"
        )
    }
}
