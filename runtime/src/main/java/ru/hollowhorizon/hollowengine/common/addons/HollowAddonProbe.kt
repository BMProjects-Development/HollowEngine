package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonBootstrapContract
import ru.hollowhorizon.hollowengine.common.utils.ModList
import java.io.File
import java.util.jar.JarFile

internal object HollowAddonProbe {
    fun isAddonJar(file: File): Boolean {
        if (!file.isFile || !file.extension.equals("jar", ignoreCase = true)) return false
        return runCatching {
            JarFile(file, false).use { jar -> jar.getJarEntry(AddonBootstrapContract.DESCRIPTOR_PATH) != null }
        }.getOrDefault(false)
    }

    fun listAddonJars(directory: File): List<File> =
        directory.listFiles { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }.orEmpty()
            .filter(::isAddonJar).sortedBy(File::getName)

    /** Only jars accepted by the active mod loader can contribute a mod-bound addon. */
    fun listEmbeddedModJars(): List<Pair<String, File>> = ModList.mods.mapNotNull { mod ->
        val file = runCatching { ModList.getFile(mod.id()) }.getOrNull() ?: return@mapNotNull null
        if (!file.isFile || !file.extension.equals("jar", ignoreCase = true)) return@mapNotNull null
        val containsDescriptor = runCatching {
            JarFile(file, false).use { jar -> jar.getJarEntry(HollowAddonLayout.EMBEDDED_MOD_DESCRIPTOR) != null }
        }.getOrDefault(false)
        if (containsDescriptor) mod.id() to file else null
    }
}
