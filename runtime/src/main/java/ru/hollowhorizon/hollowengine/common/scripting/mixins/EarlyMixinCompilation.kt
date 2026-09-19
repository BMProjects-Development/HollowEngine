package ru.hollowhorizon.hollowengine.common.scripting.mixins

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonArtifactStore
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonCandidate
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonClassLoader
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEntrypoint
import ru.hollowhorizon.hollowengine.common.scripting.deobf.CommonEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.deobf.CompilerClasspathSnapshot
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import java.io.File

/**
 * The last resort of [MixinScriptStage]. Compiling mixin scripts whose sources changed since they last
 * ran, before the game loads its first class.
 */
internal object EarlyMixinCompilation {
    const val COMPILER_ADDON = "hollowengine-compiler"
    private const val IMPLEMENTATION = "ru.hollowhorizon.hollowengine.common.compiler.tools.EarlyMixinCompilerImpl"

    class Dependencies(val mods: List<String>, val addons: List<File>)

    /** Specs of the scripts that compiled, `null` for those that declare no mixins. */
    fun compile(
        scripts: Map<ScriptId, File>,
        compiler: HollowAddonCandidate?,
        store: HollowAddonArtifactStore,
        dependencies: () -> Dependencies,
    ): Map<ScriptId, ByteArray?> {
        if (scripts.isEmpty()) return emptyMap()
        val names = scripts.keys.joinToString { ScriptRegistry.display(it) }
        if (compiler == null) {
            HollowEngine.LOGGER.warn(
                "Mixin scripts changed since the last launch, but there is no compiler addon to compile them: {}", names
            )
            return emptyMap()
        }

        HollowEngine.LOGGER.info("Compiling mixin scripts before the game starts: {}", names)
        val started = System.nanoTime()
        val results = runCatching {
            compileWith(compiler, store, classpath(compiler, dependencies), scripts)
        }.onFailure { HollowEngine.LOGGER.error("Could not compile mixin scripts before the game starts", it) }
            .getOrDefault(emptyMap())
        HollowEngine.LOGGER.info("Compiled mixin scripts in {} ms", (System.nanoTime() - started) / 1_000_000)

        val specs = LinkedHashMap<ScriptId, ByteArray?>()
        results.forEach { (id, result) ->
            result.onSuccess { spec -> specs[id] = spec }.onFailure {
                    HollowEngine.LOGGER.error(
                        "Mixin script {} does not compile: {}", ScriptRegistry.display(id), it.message
                    )
                }
        }
        return specs
    }

    private fun classpath(compiler: HollowAddonCandidate, dependencies: () -> Dependencies): List<File> {
        CompilerClasspathSnapshot.read()?.let { return it }
        HollowEngine.LOGGER.info("No recorded script classpath; building it before the game starts")
        val project = dependencies()
        return CommonEnvironment.earlyClasspath(compiler.artifactFile, project.mods, project.addons)
    }

    private fun compileWith(
        compiler: HollowAddonCandidate,
        store: HollowAddonArtifactStore,
        classpath: List<File>,
        scripts: Map<ScriptId, File>,
    ): Map<ScriptId, Result<ByteArray?>> {
        val libraries = store.extractLibraries(compiler)
        val urls =
            (listOf(compiler.classesFile, compiler.artifactFile) + libraries).distinct().map { it.toURI().toURL() }
                .toTypedArray()

        val loader = HollowAddonClassLoader(urls, HollowAddonEntrypoint::class.java.classLoader, emptyList())

        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = loader
        try {
            val instance =
                Class.forName(IMPLEMENTATION, true, loader).getConstructor(File::class.java, List::class.java)
                    .newInstance(compiler.artifactFile, classpath) as EarlyMixinCompiler
            return instance.use { it.compile(scripts) }
        } finally {
            thread.contextClassLoader = previous
        }
    }
}
