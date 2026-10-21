package ru.hollowhorizon.hollowengine.common.compiler.isolated

import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.scripting.compiler.plugin.ScriptCompilerProxy
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.ScriptDiagnosticsMessageCollector
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.convertToFirViaLightTree
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.createIsolatedCompilerState
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.failure
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.withMessageCollector
import org.jetbrains.kotlin.scripting.configuration.ScriptingConfigurationKeys
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.compiler.createHollowEngineCompilerPluginRegistrars
import ru.hollowhorizon.hollowengine.common.config.HollowEngineConfig
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.scripting.compiling.SharedScriptClasses
import ru.hollowhorizon.hollowengine.common.scripting.compiling.MemoryClassLoader
import ru.hollowhorizon.hollowengine.common.scripting.compiling.isSharedScript
import ru.hollowhorizon.hollowengine.common.scripting.compiling.sharedScriptId
import ru.hollowhorizon.hollowengine.common.scripting.compiling.sharedScriptFingerprint
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry
import ru.hollowhorizon.hollowengine.common.scripting.deobf.NeoForgeEnvironmentSetup
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.ClassRemappingSession
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.RemappingClasspath
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinSpecLayout
import ru.hollowhorizon.hollowengine.common.plugin.mixins.MixinDeclarationCollector
import ru.hollowhorizon.hollowengine.common.plugin.mixins.MixinError
import ru.hollowhorizon.hollowengine.common.plugin.mixins.MixinSpecWriter
import ru.hollowhorizon.hollowengine.common.scripting.compiling.isClientSideScript
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.utils.RuntimeFlags
import java.io.Serializable
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.compilationCache
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.disableCompilationCache
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvm.jvm

class ScriptJvmCompilerRemapped(
    definitions: List<ScriptDefinition>,
    hostConfiguration: ScriptingHostConfiguration,
    private val remappingClasspath: Lazy<RemappingClasspath>,
    private val mappings: () -> Mappings,
    private val remapToRuntime: Boolean = true,
    private val debugOutput: Boolean = true,
) : ScriptCompilerProxy {
    private val delegate = HollowEngineScriptCompiler(definitions, hostConfiguration)

    override fun compile(
        script: SourceCode,
        scriptCompilationConfiguration: ScriptCompilationConfiguration,
    ): ResultWithDiagnostics<CompiledScript> {
        val mixins = MixinDeclarationCollector()
        return when (val result = delegate.compile(script, scriptCompilationConfiguration, mixins)) {
            is ResultWithDiagnostics.Success -> {
                val spec = when (val written = writeMixinSpec(script, result.value, mixins)) {
                    is ResultWithDiagnostics.Success -> written.value
                    is ResultWithDiagnostics.Failure -> return written
                }
                ResultWithDiagnostics.Success(remapCompiledScript(result.value, spec), result.reports)
            }

            is ResultWithDiagnostics.Failure -> result
        }
    }

    /**
     * Mixin spec of the root script, as extra output file, before remapping.
     */
    private fun writeMixinSpec(
        script: SourceCode,
        compiled: CompiledScript,
        mixins: MixinDeclarationCollector,
    ): ResultWithDiagnostics<Map<String, ByteArray>> {
        val jvmScript = compiled as? KJvmCompiledScript ?: return emptyMap<String, ByteArray>().asSuccess()
        val errors = mixins.errors.toMutableList()
        val files = mixins.declarations.mapNotNullTo(HashSet()) { it.location?.file }
        if (files.size > 1) {
            errors += MixinError("Mixins can only be declared in the .mixin.kts being compiled, not in scripts it imports", null)
        }
        if (errors.isEmpty()) {
            val clientOnly = jvmScript.compilationConfiguration[ScriptCompilationConfiguration.isClientSideScript] == true
            val output = MixinSpecWriter { name -> remappingClasspath.value.findClass(name) }
                .write(jvmScript.scriptClassFQName, clientOnly, mixins.declarations)
            errors += output.errors
            val spec = output.spec
            if (errors.isEmpty()) {
                val path = jvmScript.scriptClassFQName.replace('.', '/') + MixinSpecLayout.CLASS_SUFFIX + ".class"
                return (if (spec == null) emptyMap() else mapOf(path to spec)).asSuccess()
            }
        }
        return ResultWithDiagnostics.Failure(errors.map { error -> error.toDiagnostic(script) })
    }

    private fun MixinError.toDiagnostic(script: SourceCode) = ScriptDiagnostic(
        ScriptDiagnostic.unspecifiedError,
        message,
        ScriptDiagnostic.Severity.ERROR,
        location?.file ?: script.locationId,
        location?.let { SourceCode.Location(SourceCode.Position(it.line, it.column), SourceCode.Position(it.endLine, it.endColumn)) },
    )

    private fun remapCompiledScript(script: CompiledScript, extraFiles: Map<String, ByteArray> = emptyMap()): CompiledScript {
        val jvmScript = script as? KJvmCompiledScript ?: return script
        val module = jvmScript.getCompiledModule() as? KJvmCompiledModuleInMemory ?: return jvmScript
        val outputFiles = module.compilerOutputFiles + extraFiles
        val configuration = jvmScript.compilationConfiguration.with {
            if (jvmScript.compilationConfiguration[ScriptCompilationConfiguration.isSharedScript] == true) {
                val id = jvmScript.sourceLocationId?.let(ScriptRegistry::idOfLocation)
                val fingerprint = id?.let(ScriptFingerprint::compute)
                if (id != null && fingerprint != null) {
                    sharedScriptId(id.qualified)
                    sharedScriptFingerprint(fingerprint.code)
                }
            }
        }
        val imports = jvmScript.otherScripts.map { remapCompiledScript(it) }
        val metadata = KJvmCompiledScript(
            jvmScript.sourceLocationId, configuration, jvmScript.scriptClassFQName,
            jvmScript.resultField, imports, null,
        )
        val remappingSession = if (remapToRuntime && RuntimeFlags.production && !NeoForgeEnvironmentSetup.isAvailable()) {
            ClassRemappingSession(
                mappings(),
                remappingClasspath.value,
                loader = { name -> outputFiles["$name.class"] },
            )
        } else {
            null
        }
        val remappedModule = RemappedCompiledModule(
            outputFiles.mapValues { (path, bytes) ->
                debugSave(path, bytes)
                if (path.endsWith(".class")) remappingSession?.remap(bytes) ?: bytes else bytes
            },
            listOf(metadata),
        )
        return KJvmCompiledScript(
            jvmScript.sourceLocationId,
            configuration,
            jvmScript.scriptClassFQName,
            jvmScript.resultField,
            imports,
            remappedModule,
        )
    }

    private fun debugSave(path: String, bytes: ByteArray) {
        if (debugOutput && HollowEngineConfig.debugMode) {
            val file = DirectoryManager.HOLLOW_ENGINE.resolve(".cache/compiler").resolve(path).toFile()

            if (!file.exists()) {
                file.parentFile.mkdirs()
                file.createNewFile()
            }

            file.writeBytes(bytes)
        }
    }
}

class HollowEngineScriptCompiler(
    val definitions: List<ScriptDefinition>,
    val hostConfiguration: ScriptingHostConfiguration,
) : ScriptCompilerProxy {
    override fun compile(
        script: SourceCode,
        scriptCompilationConfiguration: ScriptCompilationConfiguration,
    ): ResultWithDiagnostics<CompiledScript> = compile(script, scriptCompilationConfiguration, MixinDeclarationCollector())

    /** Compiles [script], leaving the mixin declarations the compiler plugin read in [mixins]. */
    @OptIn(ExperimentalCompilerApi::class)
    fun compile(
        script: SourceCode,
        scriptCompilationConfiguration: ScriptCompilationConfiguration,
        mixins: MixinDeclarationCollector,
    ): ResultWithDiagnostics<CompiledScript> = withMessageCollector { messageCollector ->
        withScriptCompilationCache(script, scriptCompilationConfiguration, messageCollector) {
            withConfiguredK2ScriptCompilerWithLightTree(
                definitions,
                scriptCompilationConfiguration.with {
                    hostConfiguration(this@HollowEngineScriptCompiler.hostConfiguration)
                },
                messageCollector,
                configureCompiler = {
                    put(JVMConfigurationKeys.DISABLE_STANDARD_SCRIPT_DEFINITION, true)
                    addAll(
                        ScriptingConfigurationKeys.SCRIPT_DEFINITIONS,
                        definitions
                    )
                    addAll(
                        CompilerPluginRegistrar.COMPILER_PLUGIN_REGISTRARS,
                        createHollowEngineCompilerPluginRegistrars(),
                    )
                    put(MixinDeclarationCollector.KEY, mixins)
                }
            ) {
                if (messageCollector.hasErrors()) failure(messageCollector)
                else it.compile(script)
            }
        }
    }
}

private fun <T> withConfiguredK2ScriptCompilerWithLightTree(
    definitions: List<ScriptDefinition>,
    scriptCompilationConfiguration: ScriptCompilationConfiguration,
    parentMessageCollector: MessageCollector? = null,
    configureCompiler: CompilerConfiguration.() -> Unit = {},
    body: (AnnotationFirScriptJvmCompiler) -> T,
): T {
    val disposable = Disposer.newDisposable("Default disposable for scripting compiler")
    return try {
        body(
            AnnotationFirScriptJvmCompiler(
                createIsolatedCompilerState(
                    ScriptDiagnosticsMessageCollector(parentMessageCollector), disposable,
                    scriptCompilationConfiguration,
                    scriptCompilationConfiguration[ScriptCompilationConfiguration.hostConfiguration]
                        ?: defaultJvmScriptingHostConfiguration,
                    configureCompiler
                ),
                SourceCode::convertToFirViaLightTree,
                definitions,
            )
        )
    } finally {
        Disposer.dispose(disposable)
    }
}

internal fun withScriptCompilationCache(
    script: SourceCode,
    scriptCompilationConfiguration: ScriptCompilationConfiguration,
    messageCollector: ScriptDiagnosticsMessageCollector,
    body: () -> ResultWithDiagnostics<CompiledScript>,
): ResultWithDiagnostics<CompiledScript> {
    val cache = scriptCompilationConfiguration[ScriptCompilationConfiguration.hostConfiguration]
        ?.let {
            if (it[ScriptingHostConfiguration.jvm.disableCompilationCache] == true) null
            else it[ScriptingHostConfiguration.jvm.compilationCache]
        }

    val cached = cache?.get(script, scriptCompilationConfiguration)

    return cached?.asSuccess(messageCollector.diagnostics)
        ?: body().also {
            if (cache != null && it is ResultWithDiagnostics.Success) {
                cache.store(it.value, script, scriptCompilationConfiguration)
            }
        }
}


private class RemappedCompiledModule(
    override val compilerOutputFiles: Map<String, ByteArray>,
    @Transient private val imports: List<CompiledScript> = emptyList(),
) : KJvmCompiledModuleInMemory, Serializable {
    override fun createClassLoader(baseClassLoader: ClassLoader?): ClassLoader {
        val parent = baseClassLoader ?: HollowEngine::class.java.classLoader
        return MemoryClassLoader(compilerOutputFiles, parent).apply {
            shareClassesOf(SharedScriptClasses.loadersFor(imports, compilerOutputFiles, parent))
        }
    }
}
