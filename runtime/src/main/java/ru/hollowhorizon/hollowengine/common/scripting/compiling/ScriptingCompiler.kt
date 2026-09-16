package ru.hollowhorizon.hollowengine.common.scripting.compiling

import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import java.io.File

/**
 * Everything a single compilation needs beyond the script itself.
 *
 * [extraClasspath] and [baseClassLoader] come from the namespace that owns the script, so an addon's
 * scripts see the addon's own classes and libraries. When [cacheOutput] is set the compiler also
 * writes the compiled module there, stamped with [cacheFingerprint], for later runs to reuse.
 *
 * Scripts are always compiled against Mojang names. [remapToRuntime] then maps the bytecode into the
 * namespace the game runs in; switching it off keeps the named bytecode, which is what a packaged
 * addon ships. Such a compilation is meant to be written out, not run here.
 */
data class ScriptCompilationContext(
    val extraClasspath: List<File> = emptyList(),
    val baseClassLoader: ClassLoader? = null,
    val cacheOutput: File? = null,
    val cacheFingerprint: ScriptFingerprint.Fingerprint? = null,
    val sharedCacheOutput: File? = null,
    val remapToRuntime: Boolean = true,
)

interface ScriptingCompiler {
    fun compile(name: String, code: String): Result<CompiledScript>

    fun compile(file: File): Result<CompiledScript.WithFile> = compile(file, ScriptCompilationContext())

    fun compile(file: File, context: ScriptCompilationContext): Result<CompiledScript.WithFile>
}
