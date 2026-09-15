package ru.hollowhorizon.hollowengine.common

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonContext
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEntrypoint
import ru.hollowhorizon.hollowengine.common.addons.publish
import ru.hollowhorizon.hollowengine.common.compiler.ScriptingCompilerImpl
import ru.hollowhorizon.hollowengine.common.compiler.configuration.HollowScriptConfiguration
import ru.hollowhorizon.hollowengine.common.compiler.configuration.clientSideImplicitReceivers
import ru.hollowhorizon.hollowengine.common.ide.session.AnalysisEnvironment
import ru.hollowhorizon.hollowengine.common.ide.session.EmptyLogger
import ru.hollowhorizon.hollowengine.common.scripting.DefaultScriptDefinitions
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironmentInitializer
import ru.hollowhorizon.hollowengine.common.scripting.compiling.isSharedScript
import ru.hollowhorizon.hollowengine.common.scripting.deobf.CommonEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.logI
import ru.hollowhorizon.hollowengine.logW
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.configurationDependencies
import kotlin.script.experimental.host.getScriptingClass
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.JvmGetScriptingClass
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration

class ScriptingEnvironmentInitializerImpl : ScriptingEnvironmentInitializer, HollowAddonEntrypoint {
    override suspend fun load(context: HollowAddonContext, scope: CoroutineScope) {
        val (mappings, classpath) = CommonEnvironment.setup(context.addonFile)
        val environment = initialize(
            javaHome = File(System.getProperty("java.home")),
            classpath = classpath,
            hostClasspath = classpath + context.addonFile,
            scriptTypes = DefaultScriptDefinitions.providers(),
            mappings = mappings,
        )
        context.hostServices.publish<ScriptingEnvironmentInitializer>(this)
        environment.warmUpAnalysis(scope)
    }

    override suspend fun unload(context: HollowAddonContext) {
        ScriptingEnvironment.clear()
    }

    override fun initialize(
        javaHome: File,
        classpath: List<File>,
        scriptTypes: List<ScriptClassProvider>,
        mappings: Mappings,
    ) {
        initialize(javaHome, classpath, classpath, scriptTypes, mappings)
    }

    private fun initialize(
        javaHome: File,
        classpath: List<File>,
        hostClasspath: List<File>,
        scriptTypes: List<ScriptClassProvider>,
        mappings: Mappings,
    ): ScriptingEnvironmentImpl {
        val kotlinStdlib = classpath.firstOrNull { it.name.startsWith("kotlin-stdlib-jdk8") }
            ?: classpath.firstOrNull(::containsKotlinStdlib)
        if (kotlinStdlib != null) {
            System.setProperty("kotlin.java.stdlib.jar", kotlinStdlib.absolutePath)
        }
        val environment = ScriptingEnvironmentImpl(javaHome, classpath, hostClasspath, scriptTypes, mappings)
        logI("ScriptingEnvironment loaded successfully!")
        ScriptingEnvironment.INSTANCE = environment
        return environment
    }

    private fun containsKotlinStdlib(file: File): Boolean {
        if (!file.isFile || file.extension != "jar") return false
        return runCatching {
            java.util.jar.JarFile(file).use { jar ->
                jar.getEntry("kotlin/jvm/internal/Intrinsics.class") != null
            }
        }.getOrDefault(false)
    }
}

class ScriptingEnvironmentImpl(
    override val javaHome: File,
    override val classpath: List<File>,
    private val hostClasspath: List<File> = classpath,
    scriptTypes: List<ScriptClassProvider>,
    override val mappings: Mappings,
) : ScriptingEnvironment {
    init {
        Logger.setFactory { EmptyLogger }
    }

    var scriptHostConfig = ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
        getScriptingClass(JvmGetScriptingClass())
        configurationDependencies(JvmDependency(hostClasspath))
    }
    val scriptDefinitions = scriptTypes.sortedByDescending { it.extension.length }.map { provider ->
        ScriptDefinition.FromConfigurations(
            scriptHostConfig,
            HollowScriptConfiguration(classpath) {
                baseClass.replaceOnlyDefault(KotlinType(provider.baseClass))
                fileExtension.replaceOnlyDefault(provider.extension)
                defaultImports(provider.defaultImports)
                implicitReceivers(provider.implicitReceivers.map { KotlinType(it) })
                provider.clientSideReceivers?.let { receivers ->
                    clientSideImplicitReceivers(receivers.map { KotlinType(it) })
                }
                if (provider.shared) isSharedScript(true)
            },
            ScriptEvaluationConfiguration()
        )
    }
    private val analysisEnvironment = CompletableFuture<AnalysisEnvironment>()
    private val analysisInitializationStarted = AtomicBoolean()
    private val closed = AtomicBoolean()

    override val analyzer
        get() = analysisEnvironment().analyzer
    override val compiler = ScriptingCompilerImpl(this)

    internal fun warmUpAnalysis(scope: CoroutineScope): Job = scope.launch(
        Dispatchers.Default + CoroutineName("HollowEngine-AnalysisWarmup"),
    ) {
        try {
            analysisEnvironment().warmUpClassIndex()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Throwable) {
            if (!closed.get()) logW("Failed to warm up the scripting analysis environment: $exception")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        compiler.close()
        if (analysisInitializationStarted.compareAndSet(false, true)) {
            analysisEnvironment.completeExceptionally(IllegalStateException("Scripting environment is already closed"))
        } else {
            analysisEnvironment.thenAccept(::disposeAnalysisEnvironment)
        }
    }

    private fun analysisEnvironment(): AnalysisEnvironment {
        check(!closed.get()) { "Scripting environment is already closed" }
        if (analysisInitializationStarted.compareAndSet(false, true)) {
            initializeAnalysisEnvironment()
        }
        return try {
            analysisEnvironment.join()
        } catch (exception: CompletionException) {
            throw exception.cause ?: exception
        }
    }

    private fun initializeAnalysisEnvironment() {
        if (closed.get()) {
            analysisEnvironment.completeExceptionally(IllegalStateException("Scripting environment is already closed"))
            return
        }

        val thread = Thread.currentThread()
        val previousClassLoader = thread.contextClassLoader
        thread.contextClassLoader = ScriptingEnvironmentImpl::class.java.classLoader
        try {
            val environment = AnalysisEnvironment(
                classpath.map { it.toPath() },
                scriptDefinitions,
                javaHome.toPath(),
            )
            if (closed.get()) {
                disposeAnalysisEnvironment(environment)
                analysisEnvironment.completeExceptionally(IllegalStateException("Scripting environment is already closed"))
            } else {
                analysisEnvironment.complete(environment)
            }
        } catch (exception: Throwable) {
            analysisEnvironment.completeExceptionally(exception)
        } finally {
            thread.contextClassLoader = previousClassLoader
        }
    }

    private fun disposeAnalysisEnvironment(environment: AnalysisEnvironment) {
        environment.analyzer.fileCache.forEach { file ->
            environment.analyzer.cleanupFile(file.value.file)
        }
        environment.analyzer.fileCache.clear()
        environment.dispose()
    }
}
