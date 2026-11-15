package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.bootstrap.runtime.AddonBootstrapContract
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The addons an operator switched off, kept in [directory] where the bootstrap reads them as well. */
internal class HollowAddonActivationStore(private val directory: File) {
    private val file = directory.resolve(AddonBootstrapContract.DISABLED_ADDONS_FILE)

    fun load(): Set<String> = AddonBootstrapContract.disabledAddonIds(directory)

    fun save(disabledAddonIds: Set<String>) {
        directory.mkdirs()
        val temporaryFile = file.resolveSibling(file.name + ".tmp")
        val contents = buildString {
            appendLine("# One disabled HollowEngine addon id per line.")
            disabledAddonIds.sorted().forEach(::appendLine)
        }
        Files.writeString(temporaryFile.toPath(), contents, StandardCharsets.UTF_8)
        runCatching {
            Files.move(
                temporaryFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporaryFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
