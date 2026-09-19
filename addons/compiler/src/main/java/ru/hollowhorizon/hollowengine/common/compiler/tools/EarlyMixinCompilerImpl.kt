package ru.hollowhorizon.hollowengine.common.compiler.tools

import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import ru.hollowhorizon.hollowengine.common.scripting.DefaultScriptDefinitions
import ru.hollowhorizon.hollowengine.common.scripting.deobf.CommonEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.mixins.EarlyMixinCompiler
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.useKotlinStdlibFrom
import java.io.File

/**
 * A scripting environment that knows only mixin scripts and plain scripts they import, set up from
 * what is on disk. Importing any other kind of script fails here, and such a mixin script waits for a restart.
 */
class EarlyMixinCompilerImpl(compilerJar: File, classpath: List<File>) : EarlyMixinCompiler {
    private val environment: ScriptingEnvironmentImpl

    init {
        useKotlinStdlibFrom(classpath)
        environment = ScriptingEnvironmentImpl(
            javaHome = File(System.getProperty("java.home")),
            classpath = classpath,
            hostClasspath = classpath + compilerJar,
            scriptTypes = DefaultScriptDefinitions.earlyProviders(),
            mappings = CommonEnvironment.loadMappings(compilerJar),
            debugOutput = false,
        )
    }

    override fun compile(scripts: Map<ScriptId, File>): Map<ScriptId, Result<ByteArray?>> =
        scripts.mapValues { (id, file) ->
            environment.compiler.compileMixinSpec(
                file,
                ScriptRegistry.classpath(id.namespace)
            )
        }

    override fun close() = environment.close()
}
