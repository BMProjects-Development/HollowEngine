package ru.hollowhorizon.hollowengine.common.utils

import ru.hollowhorizon.hollowengine.api.ModList
import java.io.File

object ModList : ModList {
    private lateinit var modList: ModList

    fun init(modList: ModList) {
        this.modList = modList
    }

    override fun isLoaded(modId: String): Boolean {
        return modList.isLoaded(modId)
    }

    override fun getFile(modId: String): File {
        return modList.getFile(modId)
    }

    fun getAllFiles(modId: String): List<File> = listOf(getFile(modId))
}

//? if fabric {


//?} else {
/*fun getAllMods(modId: String): List<File> {
    return collectModJars(getModFile(modId))
}
fun getModFile(modId: String): File {
    val path = ModList.get().getModFileById(modId).file.filePath
    try {
        var fileName = path.fileName.toString()
        if (!fileName.endsWith(".jar")) fileName = "$modId.jar"

        val copy = Files.newInputStream(path)

        val newFile = File("hollowengine/.cache/mods/$fileName").absoluteFile.apply {
            if (!this.parentFile.exists()) this.parentFile.mkdirs()
        }

        if (newFile.exists()) return newFile
        else newFile.createNewFile()

        FileOutputStream(newFile).use { jarOutput ->
            copy.use { reader ->
                reader.copyTo(jarOutput)
            }
        }

        return newFile
    } catch (e: AccessDeniedException) {
        return e.file
    } catch (e: Exception) {
        if (path.fileSystem::class.java.name == "cpw.mods.niofs.union.UnionFileSystem") {
            val system = path.fileSystem::class.java.getDeclaredField("basepaths")
            system.isAccessible = true
            return (system.get(path.fileSystem) as List<Path>).map { it.toFile() }.first()
        }

        return File(path.absolutePathString())
    }
}
*///?}
