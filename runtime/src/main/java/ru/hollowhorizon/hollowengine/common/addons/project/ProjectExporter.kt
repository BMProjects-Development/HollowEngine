package ru.hollowhorizon.hollowengine.common.addons.project

import ru.hollowhorizon.hollowengine.HollowEngineBuild
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonLayout
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonMappingNamespace
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonRuntimeEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import ru.hollowhorizon.hollowengine.common.scripting.compiling.ScriptCompilationContext
import ru.hollowhorizon.hollowengine.common.scripting.deobf.CommonEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptCompilationException
import ru.hollowhorizon.hollowengine.common.scripting.source.DEFAULT_SANDBOX_NAMESPACE
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.runtime.remap.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.*

class ProjectExportOptions(val output: File, val includeSources: Boolean)

/**
 * Packs project into an addon jar of the same shape a Gradle build produces, so it can be dropped
 * into `mods` or `hollowengine/addons` of a game that has no compiler.
 */
object ProjectExporter {
    fun export(options: ProjectExportOptions, progress: (ProjectMessage) -> Unit = {}): File {
        val properties = HollowProject.properties()
        val problems = properties.problems().map(::ProjectMessage).toMutableList()
        if (properties.id == DEFAULT_SANDBOX_NAMESPACE) problems += ProjectMessage(
            ProjectLang.EXPORT_DEFAULT_ID, properties.id
        )
        val icon = properties.icon.takeIf(String::isNotBlank)?.let(::iconFile)
        if (properties.icon.isNotBlank() && icon == null) problems += ProjectMessage(
            ProjectLang.EXPORT_ICON_MISSING, properties.icon
        )
        if (problems.isNotEmpty()) throw ProjectException(problems)
        if (ScriptRegistry.sandboxNamespace != properties.id || ScriptRegistry.sandbox.fingerprint != properties.version) {
            ScriptRegistry.reloadSandbox()
        }

        val work = HollowProject.root.resolve(".cache/export").resolve(System.nanoTime().toString())
        try {
            val scripts = ScriptRegistry.listIn(properties.id, ".kts")
            val compiled = if (scripts.isEmpty()) null else {
                val environment = ScriptingEnvironment.currentOrNull() ?: throw ProjectException(
                    ProjectMessage(
                        ProjectLang.EXPORT_NO_COMPILER
                    )
                )
                progress(ProjectMessage(ProjectLang.EXPORT_COMPILING, scripts.size))
                val directory = work.resolve("compiled")
                compileNamed(scripts, environment, directory)

                progress(ProjectMessage(ProjectLang.EXPORT_REMAPPING))
                val content = namedContent(directory, work)
                val table = PayloadRemapTableGenerator.generate(
                    payload = content,
                    mappings = environment.mappings,
                    classpath = environment.classpath,
                    from = "named",
                    to = "intermediary",
                    output = work.resolve("recorded.jar"),
                    relocation = PrefixRelocation.parse(HollowEngineBuild.FABRIC_RELOCATION),
                    keep = CommonEnvironment.resolveRuntimeJar()?.let(PayloadRemapTableGenerator::classNames).orEmpty(),
                ).shippedTable
                content to table
            }

            progress(ProjectMessage(ProjectLang.EXPORT_PACKING))
            writeAddon(options, properties, icon, compiled?.first, compiled?.second)
            return options.output
        } finally {
            work.deleteRecursively()
        }
    }

    private fun iconFile(path: String): File? {
        val root = HollowProject.root.toPath().toAbsolutePath().normalize()
        val file = root.resolve(path).normalize()
        return file.takeIf { it.startsWith(root) }?.toFile()?.takeIf(File::isFile)
    }

    private fun compileNamed(scripts: List<ScriptId>, environment: ScriptingEnvironment, output: File) {
        val source = ScriptRegistry.sandbox
        val failures = scripts.mapNotNull { id ->
            val sourceFile = ScriptRegistry.artifacts(id)?.sourceFile ?: return@mapNotNull null
            val fingerprint = ScriptFingerprint.compute(id)?.copy(runtime = ScriptFingerprint.NAMED_PRODUCTION_RUNTIME)
                ?: return@mapNotNull id to "no sources to compile from"
            val artifact = ScriptCache.artifactIn(output, id)
            environment.compiler.compile(
                sourceFile,
                ScriptCompilationContext(
                    extraClasspath = ScriptRegistry.classpath(source.namespace),
                    baseClassLoader = ScriptRegistry.classLoader(source.namespace),
                    cacheOutput = artifact,
                    cacheFingerprint = fingerprint,
                    sharedCacheOutput = output,
                    remapToRuntime = false,
                ),
            ).exceptionOrNull()?.let { error -> return@mapNotNull id to describe(error) }
            if (!artifact.isFile) id to "the compiler wrote no artifact" else null
        }
        if (failures.isNotEmpty()) {
            throw ProjectException(failures.map { (id, reason) ->
                ProjectMessage(ProjectLang.EXPORT_COMPILE_FAILED, ScriptRegistry.display(id), reason)
            })
        }
    }

    private fun describe(error: Throwable): String {
        val report = (error as? ScriptCompilationException)?.reports?.firstOrNull { it.severity.isError() }
            ?: return error.message ?: error.javaClass.simpleName
        return "${report.range.start.line + 1}:${report.range.start.column + 1}: ${report.message}"
    }

    private fun namedContent(compiled: File, work: File): File {
        val content = work.resolve("content.jar")
        JarOutputStream(content.outputStream().buffered()).use { jar ->
            compiled.walkTopDown().filter(File::isFile).forEach { file ->
                jar.putNextEntry(JarEntry(PRECOMPILED_SCRIPTS + file.relativeTo(compiled).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(jar) }
                jar.closeEntry()
            }
        }
        val relocation = PrefixRelocation.parse(HollowEngineBuild.FABRIC_RELOCATION)
        if (HollowAddonRuntimeEnvironment.mappingNamespace() != HollowAddonMappingNamespace.INTERMEDIARY || relocation.isEmpty) {
            return content
        }
        val restored = work.resolve("content-restored.jar")
        remapPayload(content, restored, PayloadRewrite(RelocationRemapper(relocation.reversed())))
        return restored
    }

    private fun writeAddon(
        options: ProjectExportOptions,
        properties: ProjectProperties,
        icon: File?,
        compiled: File?,
        table: PayloadRemapTable?,
    ) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue(HollowAddonLayout.FORMAT_ATTRIBUTE, HollowAddonLayout.CURRENT_FORMAT)
            mainAttributes.putValue("Created-By", "HollowEngine ${HollowEngineBuild.VERSION}")
        }
        val output = options.output.absoluteFile
        output.parentFile?.mkdirs()
        val temporary = File(output.parentFile, output.name + ".tmp")
        JarOutputStream(temporary.outputStream().buffered(), manifest).use { jar ->
            val written = HashSet<String>()
            fun put(name: String, write: (JarOutputStream) -> Unit) {
                if (!written.add(name)) return
                jar.putNextEntry(JarEntry(name))
                write(jar)
                jar.closeEntry()
            }

            fun put(name: String, file: File) = put(name) { out -> file.inputStream().use { it.copyTo(out) } }

            put(ProjectProperties.PATH) { it.write(properties.encode()) }
            HollowModMetadata.files(properties).forEach { (name, text) -> put(name) { it.write(text.toByteArray()) } }
            if (icon != null) put(properties.icon.replace('\\', '/').trimStart('/'), icon)

            HollowProject.files("scripts").filter { (path, _) -> options.includeSources || !path.endsWith(".kts") }
                .forEach { (path, file) -> put(path, file) }
            compiled?.let { content ->
                JarFile(content).use { archive ->
                    archive.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                        put(entry.name) { out -> archive.getInputStream(entry).use { it.copyTo(out) } }
                    }
                }
            }
            table?.let { remapTable ->
                val bytes = ByteArrayOutputStream().also(remapTable::write).toByteArray()
                put(HollowAddonLayout.REMAP_TABLE) { it.write(bytes) }
            }
            (HollowProject.files("assets") + HollowProject.files("data")).forEach { (path, file) -> put(path, file) }
        }
        Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
