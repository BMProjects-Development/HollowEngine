package ru.hollowhorizon.hollowengine.addons.physics

import com.github.stephengold.joltjni.Jolt
import com.github.stephengold.joltjni.NativeLibraryLoader
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*

/**
 * Setup Jolt: unpacks the native library for this device and registers the library's globals.
 */
object JoltNatives {
    private var outcome: Result<Unit>? = null

    val isAvailable: Boolean get() = ensureLoaded().isSuccess

    @Synchronized
    fun ensureLoaded(): Result<Unit> = outcome ?: load().also { outcome = it }

    private fun load(): Result<Unit> = runCatching {
        val platform = currentPlatform() ?: error("Jolt has no native library for $OS_NAME/$OS_ARCH")
        val resource = "${platform.directory}/com/github/stephengold/${platform.library}"
        val target = nativesDirectory().resolve(platform.library)

        extract(resource, target)
        check(NativeLibraryLoader.loadLibrary(target.toAbsolutePath().toString())) {
            "Jolt native library at $target could not be loaded"
        }

        Jolt.registerDefaultAllocator()
        Jolt.installDefaultTraceCallback()
        Jolt.newFactory()
        Jolt.registerTypes()

        HollowEngine.LOGGER.info("Jolt {} loaded for {}", Jolt.versionString(), platform.directory)
    }.onFailure { HollowEngine.LOGGER.error("Physics is unavailable: Jolt could not be loaded", it) }

    private fun extract(resource: String, target: Path) {
        val loader = javaClass.classLoader
        val expectedSize = loader.getResourceAsStream(resource)?.use { it.readBytes().size.toLong() }
            ?: error("Jolt native library '$resource' is missing from the addon")
        if (Files.exists(target) && Files.size(target) == expectedSize) return

        Files.createDirectories(target.parent)
        loader.getResourceAsStream(resource)!!.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun nativesDirectory(): Path = DirectoryManager.HOLLOW_ENGINE.resolve(".cache/natives")

    private fun currentPlatform(): JoltPlatform? {
        val arm = OS_ARCH.startsWith("aarch64") || OS_ARCH.startsWith("arm64")
        return when {
            OS_NAME.startsWith("windows") -> JoltPlatform("windows/x86-64", "joltjni.dll")
            OS_NAME.startsWith("mac") || OS_NAME.startsWith("darwin") -> JoltPlatform(
                if (arm) "osx/aarch64" else "osx/x86-64", "libjoltjni.dylib"
            )

            OS_NAME.startsWith("linux") -> JoltPlatform(if (arm) "linux/aarch64" else "linux/x86-64", "libjoltjni.so")

            else -> null
        }
    }

    private val OS_NAME = System.getProperty("os.name").lowercase(Locale.ROOT)
    private val OS_ARCH = System.getProperty("os.arch").lowercase(Locale.ROOT)

    private class JoltPlatform(val directory: String, val library: String)
}
