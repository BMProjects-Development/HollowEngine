package ru.hollowhorizon.hollowengine.common.scripting.deobf

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.HollowEngineBuild
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import java.io.File

/**
 * The classpath scripts were last compiled against, kept for the next launch.
 */
object CompilerClasspathSnapshot {
    private val file: File
        get() = DirectoryManager.HOLLOW_ENGINE.resolve(
            "cache/mixins/compiler-classpath-${ScriptFingerprint.currentRuntimeIdentity.replace('/', '-')}.txt",
        ).toFile()

    fun write(classpath: List<File>) {
        val runtime = CommonEnvironment.resolveRuntimeJar()?.absoluteFile
        runCatching {
            file.parentFile.mkdirs()
            val entries = classpath.map { entry -> if (entry.absoluteFile == runtime) RUNTIME else entry.absolutePath }
            file.writeText((header() + entries).joinToString("\n"))
        }.onFailure { HollowEngine.LOGGER.warn("Failed to record the script classpath", it) }
    }

    /**
     * The recorded classpath, or `null` when there is none this launch can use.
     */
    fun read(): List<File>? {
        val lines = runCatching { file.takeIf(File::isFile)?.readLines() }.getOrNull() ?: return null
        val header = header()
        if (lines.size < header.size || lines.subList(0, header.size) != header) {
            HollowEngine.LOGGER.info(
                "The recorded script classpath was made for {}, this launch is {}",
                lines.take(header.size),
                header
            )
            return null
        }
        val recorded = lines.drop(header.size).filter(String::isNotBlank).map { entry ->
            if (entry != RUNTIME) return@map File(entry)
            CommonEnvironment.resolveRuntimeJar() ?: return null.also {
                HollowEngine.LOGGER.info("Cannot tell which jar the engine runs from, so the recorded script classpath is unusable")
            }
        }
        val classpath = recorded.filter(File::exists)
        if (classpath.size < recorded.size) {
            HollowEngine.LOGGER.info(
                "{} entries of the recorded script classpath are gone; compiling without them",
                recorded.size - classpath.size
            )
        }
        return classpath.takeIf { it.isNotEmpty() }
    }

    /**
     * Stands for the jar the engine runs from, when scripts compile against it as it is. That jar is named
     * after its content, so each build of the engine is a different file.
     */
    private const val RUNTIME = "<runtime>"

    private fun header() = listOf(
        "engine=${HollowEngineBuild.VERSION}",
        "runtime=${ScriptFingerprint.currentRuntimeIdentity}",
    )
}
