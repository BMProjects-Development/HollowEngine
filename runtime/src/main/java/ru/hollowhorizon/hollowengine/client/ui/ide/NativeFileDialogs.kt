package ru.hollowhorizon.hollowengine.client.ui.ide

import org.lwjgl.system.MemoryStack
import org.lwjgl.util.tinyfd.TinyFileDialogs
import java.io.File

/**
 * The operating system's own open and save dialogs. Each call blocks until the user answers, so it
 * belongs on a background thread, never on the render thread.
 */
internal object NativeFileDialogs {
    fun saveJar(title: String, suggested: File): File? = save(title, suggested, JAR)

    fun openJar(title: String, directory: File): File? = open(title, directory, JAR)

    fun saveJson(title: String, suggested: File): File? = save(title, suggested, JSON)

    fun openJson(title: String, directory: File): File? = open(title, directory, JSON)

    private fun save(title: String, suggested: File, type: FileType): File? = MemoryStack.stackPush().use { stack ->
        val filters = stack.mallocPointer(1).put(stack.UTF8(type.pattern)).flip()
        TinyFileDialogs.tinyfd_saveFileDialog(title, suggested.absolutePath, filters, type.description)?.let(::File)
            ?.let { file ->
                if (file.extension.equals(
                        type.extension,
                        ignoreCase = true
                    )
                ) file else File(file.path + "." + type.extension)
            }
    }

    private fun open(title: String, directory: File, type: FileType): File? = MemoryStack.stackPush().use { stack ->
        val filters = stack.mallocPointer(1).put(stack.UTF8(type.pattern)).flip()
        val start = directory.absolutePath + File.separator
        TinyFileDialogs.tinyfd_openFileDialog(title, start, filters, type.description, false)?.let(::File)
            ?.takeIf(File::isFile)
    }

    private class FileType(val extension: String, val description: String) {
        val pattern = "*.$extension"
    }

    private val JAR = FileType("jar", "Java archive (*.jar)")
    private val JSON = FileType("json", "JSON (*.json)")
}
