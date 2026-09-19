package ru.hollowhorizon.hollowengine.common.compiler

import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import ru.hollowhorizon.hollowengine.common.compiler.caching.saveScriptToJar
import ru.hollowhorizon.hollowengine.common.compiler.isolated.ScriptJvmCompilerRemapped
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import ru.hollowhorizon.hollowengine.common.scripting.compiling.CompiledScript
import ru.hollowhorizon.hollowengine.common.scripting.compiling.ScriptCompilationContext
import ru.hollowhorizon.hollowengine.common.scripting.compiling.ScriptingCompiler
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.RemappingClasspath
import ru.hollowhorizon.hollowengine.common.scripting.ide.*
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinSpecLayout
import ru.hollowhorizon.hollowengine.logW
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.host.StringScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlin.script.experimental.api.CompiledScript as KotlinScript

class ScriptingCompilerImpl(val environment: ScriptingEnvironmentImpl) : ScriptingCompiler {
    private val remappingClasspath = lazy { RemappingClasspath(environment.classpath) }

    override fun compile(file: File, context: ScriptCompilationContext): Result<CompiledScript.WithFile> {
        val definition = environment.scriptDefinitions.getDefinitionFor(file.name)
        val result = runScriptingBlocking {
            newCompiler(definition, context.remapToRuntime)(
                FileScriptSource(file),
                definition.compilationConfiguration.withClasspath(context.extraClasspath),
            )
        }

        if (result !is ResultWithDiagnostics.Success) {
            return Result.failure(ScriptCompilationException(file.name, result.reports.map { it.convert() }))
        }

        context.cacheOutput?.let { output ->
            cache(result.value, output, context.cacheFingerprint, context.sharedCacheOutput)
        }

        return Result.success(
            CompiledScript.WithFile(
                CompiledScriptImpl(
                    file.name,
                    result.value,
                    definition.evaluationConfiguration!!.withBaseClassLoader(context.baseClassLoader),
                ),
                file,
            )
        )
    }

    override fun compile(
        name: String,
        code: String,
    ): Result<CompiledScript> {
        val definition = environment.scriptDefinitions.getDefinitionFor(name)
        val result = runScriptingBlocking {
            newCompiler(definition)(StringScriptSource(code), definition.compilationConfiguration)
        }

        return if (result is ResultWithDiagnostics.Success) {
            Result.success(CompiledScriptImpl(name, result.value, definition.evaluationConfiguration!!))
        } else {
            Result.failure(ScriptCompilationException(name, result.reports.map { it.convert() }))
        }
    }

    /**
     * Compiles [file] with mixin spec, `null` when it declares no mixins.
     */
    fun compileMixinSpec(file: File, extraClasspath: List<File>): Result<ByteArray?> {
        val definition = environment.scriptDefinitions.getDefinitionFor(file.name)
        val result = runScriptingBlocking {
            newCompiler(definition)(
                FileScriptSource(file),
                definition.compilationConfiguration.withClasspath(extraClasspath)
            )
        }
        if (result !is ResultWithDiagnostics.Success) {
            return Result.failure(ScriptCompilationException(file.name, result.reports.map { it.convert() }))
        }
        val script = result.value as KJvmCompiledScript
        val module = script.getCompiledModule() as KJvmCompiledModuleInMemory
        val path = script.scriptClassFQName.replace('.', '/') + MixinSpecLayout.CLASS_SUFFIX + ".class"
        return Result.success(module.compilerOutputFiles[path])
    }

    private fun newCompiler(definition: ScriptDefinition, remapToRuntime: Boolean = true) = JvmScriptCompiler(
        definition.hostConfiguration,
        ScriptJvmCompilerRemapped(
            environment.scriptDefinitions,
            definition.hostConfiguration,
            remappingClasspath,
            mappings = { environment.mappings },
            remapToRuntime = remapToRuntime,
            debugOutput = environment.debugOutput,
        ),
    )

    fun close() {
        if (remappingClasspath.isInitialized()) remappingClasspath.value.close()
    }

    /**
     * Stores the compiled module so later runs, including ones where this addon is not installed, can
     * load it straight from disk. A cache that cannot be written is not a reason to fail a compilation.
     */
    private fun cache(
        compiled: KotlinScript,
        output: File,
        fingerprint: ScriptFingerprint.Fingerprint?,
        sharedDirectory: File?,
    ) {
        if (fingerprint == null) return
        val jvmScript = compiled as? KJvmCompiledScript ?: return
        runCatching {
            output.parentFile?.mkdirs()
            val temporary = File(output.parentFile, output.name + ".tmp")
            jvmScript.saveScriptToJar(temporary, fingerprint, sharedDirectory)
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { logW("Failed to cache the compiled script '$output': $it") }
    }
}

private fun ScriptCompilationConfiguration.withClasspath(extra: List<File>): ScriptCompilationConfiguration =
    if (extra.isEmpty()) this else with { jvm { updateClasspath(extra) } }

private fun ScriptEvaluationConfiguration.withBaseClassLoader(
    classLoader: ClassLoader?,
): ScriptEvaluationConfiguration =
    if (classLoader == null) this else with { ScriptEvaluationConfiguration.jvm.baseClassLoader(classLoader) }

fun List<ScriptDefinition.FromConfigurations>.getDefinitionFor(name: String): ScriptDefinition {
    return sortedWith(compareByDescending<ScriptDefinition.FromConfigurations> { it.fileExtension.length }.thenByDescending { it.fileExtension }).first {
        name.endsWith(
            it.fileExtension
        )
    }
}

fun ScriptDiagnostic.convert(): Diagnostic {
    return Diagnostic(this.location?.let {
        Range(
            Position(it.start.line, it.start.col),
            Position(it.end?.line ?: it.start.line, it.end?.col ?: it.start.col)
        )
    } ?: Range(Position(-1, -1), Position(-1, -1)), when (this.severity) {
        ScriptDiagnostic.Severity.DEBUG -> Severity.DEBUG
        ScriptDiagnostic.Severity.INFO -> Severity.INFO
        ScriptDiagnostic.Severity.WARNING -> Severity.WARNING
        ScriptDiagnostic.Severity.ERROR -> Severity.ERROR
        ScriptDiagnostic.Severity.FATAL -> Severity.FATAL
    }, this.message)
}
