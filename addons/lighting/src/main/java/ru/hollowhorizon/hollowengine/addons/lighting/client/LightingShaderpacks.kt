package ru.hollowhorizon.hollowengine.addons.lighting.client

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipFile
import net.irisshaders.iris.Iris
import org.qualet.irl.patcher.IrlPatchApplier
import org.qualet.irl.patcher.IrlPatchParser
import org.qualet.irl.patcher.PatchLibrary
import org.qualet.irl.patcher.PatchResult
import org.qualet.irl.patcher.Shaderpacks
import ru.hollowhorizon.hollowengine.fabric.internal.IrisHelper

/** Existing IRLights packs need no conversion. These helpers only prepare new copies from clean packs. */
object LightingShaderpacks {
    private const val ORIGINAL = "hollowengine-lighting.properties"

    fun apply(packName: String, patchName: String, validateOnly: Boolean = false): PatchResult {
        val source = child(Shaderpacks.dir(), packName)
        val patchFile = child(PatchLibrary.dir(), patchName)
        val patch = IrlPatchParser.parse(Files.readString(patchFile))
        if (validateOnly) return IrlPatchApplier.validate(source, patch)
        val outputName = Shaderpacks.outputName(patch, packName, true)
        val output = child(Shaderpacks.dir(), outputName)
        val result = IrlPatchApplier.apply(source, output, patch)
        if (result.ok) {
            Files.newOutputStream(output.resolve(ORIGINAL)).use { stream ->
                Properties().apply { setProperty("source", packName) }.store(stream, "HollowEngine light pack source")
            }
        }
        return result
    }

    /** Called while unloading, before freeing GPU buffers that a patched pack would otherwise keep reading. */
    internal fun restoreOriginal() {
        if (!IrisHelper.hasIris) return
        val config = Iris.getIrisConfig()
        val name = config.shaderPackName.orElse(null) ?: return
        val pack = child(Shaderpacks.dir(), name)
        val original = if (Files.isDirectory(pack)) {
            val record = pack.resolve(ORIGINAL)
            if (Files.isRegularFile(record)) Files.newInputStream(record).use { input ->
                Properties().apply { load(input) }.getProperty("source")
            } else null
        } else null
        val patched = original != null || isPatched(pack)
        if (!patched) return
        if (original != null && Files.exists(child(Shaderpacks.dir(), original))) config.setShaderPackName(original)
        else config.setShadersEnabled(false)
        config.save()
        Iris.reload()
    }

    private fun isPatched(pack: Path): Boolean = when {
        Files.isDirectory(pack) -> Files.exists(pack.resolve("irlite_patched.txt")) ||
            Files.exists(pack.resolve("shaders/lib/irlite/irlite_lights.glsl"))
        Files.isRegularFile(pack) && pack.fileName.toString().endsWith(".zip", true) -> ZipFile(pack.toFile()).use { zip ->
            zip.entries().asSequence().any { it.name.endsWith("irlite_patched.txt") || it.name.endsWith("/irlite_lights.glsl") }
        }
        else -> false
    }

    private fun child(root: Path, name: String): Path {
        require(name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') && !name.contains(':')) {
            "Expected a file name inside $root"
        }
        return root.resolve(name)
    }
}
