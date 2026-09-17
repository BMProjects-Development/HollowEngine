package ru.hollowhorizon.hollowengine.runtime.remap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.remap
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.system.exitProcess

/**
 * Merges the two loader jars into one mod that both NeoForge and Fabric can load.
 */
object UniversalJarTool {
    private val TEXT_EXTENSIONS = setOf("json", "toml", "cfg", "mcmeta", "accesswidener", "txt")
    private const val REMAP_TABLE = "META-INF/hollowengine/runtime/payload-remap-fabric.tbl.gz"
    private const val FABRIC_DESCRIPTOR = "fabric.mod.json"
    private const val NEOFORGE_DESCRIPTOR = "META-INF/neoforge.mods.toml"
    private const val CONNECTOR_PLACEHOLDER = "connector:placeholder"

    @JvmStatic
    fun main(args: Array<String>) {
        val options = Options.parse(args)
        val problems = merge(options)

        if (problems.isNotEmpty()) {
            System.err.println("Universal jar is not consistent:")
            problems.take(30).forEach { System.err.println("  $it") }
            if (problems.size > 30) System.err.println("  ... and ${problems.size - 30} more")
            exitProcess(1)
        }

        val megabytes = options.output.length() / 1024 / 1024
        println("Universal jar: ${options.output.name}, $megabytes MB, ${options.relocatedClasses} classes relocated")
    }

    private fun merge(options: Options): List<String> {
        val written = LinkedHashMap<String, ByteArray>()
        val conflicts = ArrayList<String>()
        options.output.parentFile?.mkdirs()

        JarOutputStream(options.output.outputStream().buffered()).use { out ->
            fun write(name: String, content: ByteArray, source: String) {
                val previous = written[name]
                if (previous != null) {
                    if (!previous.contentEquals(content)) conflicts += "$name differs between platforms ($source)"
                    return
                }

                written[name] = content
                out.putNextEntry(JarEntry(name))
                out.write(content)
                out.closeEntry()
            }

            JarFile(options.neoforge).use { jar ->
                jar.entries().asSequence().forEach { entry ->
                    if (entry.name in options.overrides) return@forEach
                    val bytes = if (entry.isDirectory) ByteArray(0) else jar.getInputStream(entry).use { it.readBytes() }
                    write(entry.name, bytes, "neoforge")
                }
            }

            JarFile(options.fabric).use { jar ->
                val remapper = TableRemapper(options.relocationTable(jar))
                jar.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) {
                        val relocated = options.relocation.relocate(entry.name.removeSuffix("/"))
                        write("$relocated/", ByteArray(0), "fabric")
                        return@forEach
                    }

                    val bytes = jar.getInputStream(entry).use { it.readBytes() }
                    when {
                        entry.name.endsWith(".class") -> {
                            val relocated = options.relocation.relocate(entry.name.removeSuffix(".class"))
                            write("$relocated.class", bytes.remap(remapper), "fabric")
                        }

                        entry.name.substringAfterLast('.', "") in TEXT_EXTENSIONS ->
                            write(options.rename(entry.name), options.rewriteText(bytes), "fabric")

                        else -> write(options.rename(entry.name), bytes, "fabric")
                    }
                }
            }
        }

        return conflicts + verify(written, options)
    }

    private fun verify(entries: Map<String, ByteArray>, options: Options): List<String> = buildList {
        listOf(FABRIC_DESCRIPTOR, NEOFORGE_DESCRIPTOR).forEach { descriptor ->
            if (descriptor !in entries) add("$descriptor is missing")
        }

        entries[FABRIC_DESCRIPTOR]?.let { bytes ->
            val text = String(bytes, Charsets.UTF_8)
            if (text.contains("\${")) add("$FABRIC_DESCRIPTOR still holds unexpanded template placeholders")
            declaredConfigs(bytes).forEach { config ->
                if (config !in entries) add("$FABRIC_DESCRIPTOR declares missing mixin config $config")
            }
        }

        entries[NEOFORGE_DESCRIPTOR]?.let { bytes ->
            // Without it Sinytra Connector loads the Fabric half of the jar next to the NeoForge one.
            if (CONNECTOR_PLACEHOLDER !in String(bytes, Charsets.UTF_8)) {
                add("$NEOFORGE_DESCRIPTOR does not mark the jar as $CONNECTOR_PLACEHOLDER")
            }
        }

        entries.keys.filter { it.endsWith(".mixins.json") }.forEach { config ->
            mixinClasses(entries.getValue(config)).forEach { className ->
                if ("$className.class" !in entries) add("$config references missing class $className")
            }
        }

        entries[REMAP_TABLE]?.let { bytes ->
            val table = PayloadRemapTable.read(bytes.inputStream())
            table.classes.values.filter(options::isRelocationTarget).forEach { target ->
                if ("$target.class" !in entries) add("remap table sends the payload to missing class $target")
            }
        }
    }

    private fun declaredConfigs(bytes: ByteArray): List<String> {
        val root = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject ?: return emptyList()
        return (root["mixins"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
    }

    private fun mixinClasses(bytes: ByteArray): List<String> {
        val root = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject ?: return emptyList()
        val packageName = (root["package"]?.jsonPrimitive?.content ?: return emptyList()).replace('.', '/')

        return buildList {
            listOf("mixins", "client", "server").forEach { section ->
                (root[section] as? JsonArray)?.forEach { add("$packageName/${it.jsonPrimitive.content.replace('.', '/')}") }
            }
            root["plugin"]?.jsonPrimitive?.content?.let { add(it.replace('.', '/')) }
        }
    }

    private class Options(
        val neoforge: File,
        val fabric: File,
        val output: File,
        val relocation: PrefixRelocation,
        private val relocationTargets: List<String>,
        private val renames: Map<String, String>,
        val overrides: Set<String>,
    ) {
        var relocatedClasses: Int = 0
            private set

        fun rename(name: String): String = renames[name] ?: name

        fun rewriteText(bytes: ByteArray): ByteArray {
            var text = relocation.relocateText(String(bytes, Charsets.UTF_8))
            renames.forEach { (from, to) -> text = text.replace(from, to) }
            return text.toByteArray(Charsets.UTF_8)
        }

        fun isRelocationTarget(internalName: String): Boolean =
            relocationTargets.any { internalName == it || internalName.startsWith("$it/") }

        fun relocationTable(jar: JarFile): PayloadRemapTable {
            val classes = jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .map { it.name.removeSuffix(".class") }
                .mapNotNull { name -> relocation.relocate(name).takeIf { it != name }?.let { name to it } }
                .toMap()

            relocatedClasses = classes.size
            return PayloadRemapTable("named", "named", classes, emptyMap(), emptyMap())
        }

        companion object {
            fun parse(args: Array<String>): Options {
                val values = HashMap<String, String>()
                var index = 0
                while (index < args.size - 1) {
                    values[args[index].removePrefix("--")] = args[index + 1]
                    index += 2
                }

                fun require(name: String) = File(values[name] ?: error("Missing --$name"))

                val relocate = values["relocate"]
                return Options(
                    neoforge = require("neoforge"),
                    fabric = require("fabric"),
                    output = require("output"),
                    relocation = PrefixRelocation.parse(relocate),
                    relocationTargets = pairs(relocate).values.map { it.replace('.', '/') },
                    renames = pairs(values["rename"]),
                    overrides = values["override"].orEmpty().split(',').filter(String::isNotBlank).toSet(),
                )
            }

            private fun pairs(value: String?): Map<String, String> = value.orEmpty()
                .split(',')
                .filter(String::isNotBlank)
                .associate { rule ->
                    val (from, to) = rule.split('=', limit = 2)
                    from.trim() to to.trim()
                }
        }
    }
}
