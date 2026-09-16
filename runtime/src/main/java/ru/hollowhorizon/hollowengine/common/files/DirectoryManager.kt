package ru.hollowhorizon.hollowengine.common.files

import ru.hollowhorizon.hollowengine.common.scripting.NODE_SCRIPT_EXTENSION
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.io.File
import java.nio.file.Path

object DirectoryManager {
    val HOLLOW_ENGINE: Path by lazy {
        File("hollowengine").absoluteFile.apply {
            if (!exists()) mkdirs()
        }.toPath().normalize()
    }

    /** Compiled script artifacts, one self-contained jar per root script. */
    val SCRIPT_CACHE: File get() = HOLLOW_ENGINE.resolve("cache/scripts").toFile()

    /** Sources extracted from addon jars so the compiler can work with real files. */
    val SCRIPT_SOURCE_CACHE: File get() = HOLLOW_ENGINE.resolve("cache/script-sources").toFile()

    /** Ahead-of-time compiled artifacts extracted from addon jars. */
    val SCRIPT_BUNDLE_CACHE: File get() = HOLLOW_ENGINE.resolve("cache/script-bundles").toFile()

    @JvmStatic
    fun File.toReadablePath(): String {
        return toPath().toReadablePath()
    }

    @JvmStatic
    fun Path.toReadablePath(): String {
        return HOLLOW_ENGINE.relativize(toAbsolutePath().normalize()).toString().replace("\\", "/")
    }

    @JvmStatic
    fun String.fromReadablePath(): File {
        return HOLLOW_ENGINE.resolve(this).toFile()
    }

    val componentScripts: List<ScriptId> get() = ScriptRegistry.list(".$NODE_SCRIPT_EXTENSION")
}
