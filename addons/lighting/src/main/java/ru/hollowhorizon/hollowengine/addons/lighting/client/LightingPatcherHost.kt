package ru.hollowhorizon.hollowengine.addons.lighting.client

import java.io.InputStream
import java.nio.file.Path
import net.minecraft.Util
import net.minecraft.client.Minecraft
import org.qualet.irl.patcher.PatcherHost

internal object LightingPatcherHost : PatcherHost {
    val bundled = listOf("bliss", "bsl", "complementaryreimagined", "iterationrp", "photon", "rethinkingvoxels", "solas")

    override fun gameDir(): Path = Minecraft.getInstance().gameDirectory.toPath()
    override fun shaderpacksDir(): Path = gameDir().resolve("shaderpacks")
    override fun listShaderpacks(): List<String> = emptyList()
    override fun openFolder(dir: Path) = Util.getPlatform().openFile(dir.toFile())
    override fun patchesDirName() = "hollowengine/lighting"
    override fun bundledPatches() = bundled.map { "$it.irlights" }
    override fun openBundledPatch(name: String): InputStream? = javaClass.classLoader
        .getResourceAsStream("assets/hollowengine-lighting/patches/$name")
}
