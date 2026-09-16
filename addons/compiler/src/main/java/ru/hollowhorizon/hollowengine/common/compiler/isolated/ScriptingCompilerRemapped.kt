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
import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.compiling.SharedScriptClasses
import ru.hollowhorizon.hollowengine.common.scripting.deobf.NeoForgeEnvironmentSetup
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.ClassRemappingSession
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.RemappingClasspath
import ru.hollowhorizon.hollowengine.common.utils.isProduction
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.Serializable
import java.security.ProtectionDomain
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
    private val remapToRuntime: Boolean = true,
) : ScriptCompilerProxy {
    private val delegate = HollowEngineScriptCompiler(definitions, hostConfiguration)

    override fun compile(
        script: SourceCode,
        scriptCompilationConfiguration: ScriptCompilationConfiguration,
    ): ResultWithDiagnostics<CompiledScript> {
        return when (val result = delegate.compile(script, scriptCompilationConfiguration)) {
            is ResultWithDiagnostics.Success -> {
                ResultWithDiagnostics.Success(remapCompiledScript(result.value), result.reports)
            }

            is ResultWithDiagnostics.Failure -> result
        }
    }

    private fun remapCompiledScript(script: CompiledScript): CompiledScript {
        val jvmScript = script as? KJvmCompiledScript ?: return script
        val module = jvmScript.getCompiledModule() as? KJvmCompiledModuleInMemory ?: return jvmScript
        val outputFiles = module.compilerOutputFiles
        val remappingSession = if (remapToRuntime && isProduction && !NeoForgeEnvironmentSetup.isAvailable()) {
            val environment = ScriptingEnvironment.INSTANCE
            ClassRemappingSession(
                environment.mappings,
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
            jvmScript.otherScripts,
        )
        return KJvmCompiledScript(
            jvmScript.sourceLocationId,
            jvmScript.compilationConfiguration,
            jvmScript.scriptClassFQName,
            jvmScript.resultField,
            jvmScript.otherScripts.map(::remapCompiledScript),
            remappedModule,
        )
    }

    private fun debugSave(path: String, bytes: ByteArray) {
        if (HollowEngineConfig.debugMode) {
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
    @OptIn(ExperimentalCompilerApi::class)
    override fun compile(
        script: SourceCode,
        scriptCompilationConfiguration: ScriptCompilationConfiguration,
    ): ResultWithDiagnostics<CompiledScript> = withMessageCollector { messageCollector ->
        withScriptCompilationCache(script, scriptCompilationConfiguration, messageCollector) {
            withConfiguredK2ScriptCompilerWithLightTree(
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
                }
            ) {
                if (messageCollector.hasErrors()) failure(messageCollector)
                else it.compile(script)
            }
        }
    }
}

private fun <T> withConfiguredK2ScriptCompilerWithLightTree(
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
                SourceCode::convertToFirViaLightTree
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
        return RemappedCompiledScriptClassLoader(
            compilerOutputFiles,
            parent,
            SharedScriptClasses.loadersFor(imports, compilerOutputFiles, parent),
        )
    }
}

private class RemappedCompiledScriptClassLoader(
    private val files: Map<String, ByteArray>,
    parent: ClassLoader?,
    private val shared: Map<String, ClassLoader>,
) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        val owner = sharedOwnerOf(name) ?: return super.loadClass(name, resolve)
        return owner.loadClass(name).also { if (resolve) resolveClass(it) }
    }

    /** Nested and synthetic classes belong to the script that declared them: `Quests$Payload` to `Quests`. */
    private fun sharedOwnerOf(name: String): ClassLoader? {
        if (shared.isEmpty()) return null
        shared[name]?.let { return it }
        val outer = name.substringBefore('$')
        return if (outer == name) null else shared[outer]
    }

    override fun findClass(name: String): Class<*> {
        val path = name.replace('.', '/') + ".class"
        val bytes = files[path] ?: throw ClassNotFoundException(name)
        val protectionDomain = ProtectionDomain(null, null)
        return defineClass(name, bytes, 0, bytes.size, protectionDomain)
    }

    override fun getResourceAsStream(name: String): InputStream? {
        return files[name]?.let(::ByteArrayInputStream)
    }
}
