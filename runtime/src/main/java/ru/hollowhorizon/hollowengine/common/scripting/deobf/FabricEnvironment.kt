//? if fabric {

package ru.hollowhorizon.hollowengine.common.scripting.deobf

import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.impl.FabricLoaderImpl
import net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.remapJars
import ru.hollowhorizon.hollowengine.common.utils.RuntimeFlags
import ru.hollowhorizon.hollowengine.fabric.findField
import java.io.File
import java.nio.file.Path

object FabricEnvironmentSetup : EnvironmentSetup {
    override fun setup(mappings: Mappings, outputDir: File): List<File> {
        val gameProvider =
            (FabricLoader.getInstance() as FabricLoaderImpl).gameProvider as MinecraftGameProvider
        val libs: List<Path> = findField(gameProvider, "miscGameLibraries")
        val gameJars: List<Path> = findField(gameProvider, "gameJars")
        val logJars: Set<Path> = findField(gameProvider, "logJars")
        val parentClassPath: Collection<Path> = findField(gameProvider, "validParentClassPath")

        if (RuntimeFlags.production) {
            val remapped = remapJars(
                mappings,
                gameJars.map { it.toFile() },
                outputDir,
                from = "intermediary",
                to = "named"
            )

            return (libs + logJars + parentClassPath).map { it.toFile() } + remapped
        } else {
            return (libs + gameJars + logJars + parentClassPath).map { it.toFile() }
        }
    }
}

//?}