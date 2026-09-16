package ru.hollowhorizon.hollowengine.client.ui.ide

import org.lwjgl.system.MemoryStack
import org.lwjgl.util.tinyfd.TinyFileDialogs
import java.io.File

/**
 * The operating system's own open and save dialogs. Each call blocks until the user answers, so it
 * belongs on a background thread, never on the render thread.
 */
internal object NativeFileDialogs {
    fun saveJar(title: String, suggested: File): File? = MemoryStack.stackPush().use { stack ->
        val filters = stack.mallocPointer(1).put(stack.UTF8(JAR_PATTERN)).flip()
        TinyFileDialogs.tinyfd_saveFileDialog(title, suggested.absolutePath, filters, JAR_DESCRIPTION)
            ?.let(::File)
            ?.let { file -> if (file.extension.equals("jar", ignoreCase = true)) file else File(file.path + ".jar") }
    }

    fun openJar(title: String, directory: File): File? = MemoryStack.stackPush().use { stack ->
        val filters = stack.mallocPointer(1).put(stack.UTF8(JAR_PATTERN)).flip()
        val start = directory.absolutePath + File.separator
        TinyFileDialogs.tinyfd_openFileDialog(title, start, filters, JAR_DESCRIPTION, false)
            ?.let(::File)
            ?.takeIf(File::isFile)
    }

    private const val JAR_PATTERN = "*.jar"
    private const val JAR_DESCRIPTION = "Java archive (*.jar)"
}
