package ru.hollowhorizon.hollowengine.common.scripting.compiling

import kotlinx.coroutines.runBlocking
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import ru.hollowhorizon.hollowengine.common.scripting.ide.*
import java.io.File
import java.io.InputStream
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.reflect.InvocationTargetException
import java.security.ProtectionDomain
import java.util.*
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import kotlin.reflect.KClass
import kotlin.script.experimental.api.*
import kotlin.script.experimental.impl._languageVersion
import kotlin.script.experimental.jvm.JvmScriptEvaluationConfigurationKeys
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvm.impl.createScriptFromClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.util.PropertiesCollection
import kotlin.script.experimental.api.CompiledScript as KotlinCompiledScript

/**
 * Loads a script compiled earlier by this game, by an addon build, or by the Gradle build. The
 * classes are defined under [baseClassLoader], which is how an addon's scripts keep seeing the addon's
 * own classes without the compiler being installed.
 */
fun File.loadKotlinCompiledScriptFromJar(baseClassLoader: ClassLoader? = null): CompiledScript {
    val className = inputStream().use { input ->
        JarInputStream(input).use { jar ->
            jar.manifest.mainAttributes.getValue("Main-Class")
                ?: throw IllegalArgumentException("No Main-Class manifest attribute")
        }
    }
    val script = KJvmCompiledScriptFromJar(className, this)
    val evaluationConfiguration = ScriptEvaluationConfiguration {
        baseClassLoader?.let { ScriptEvaluationConfiguration.jvm.baseClassLoader(it) }
    }
    return KotlinCompiledScriptJar(nameWithoutExtension, script, evaluationConfiguration)
}

internal class KotlinCompiledScriptJar(
    override val name: String,
    private val script: KJvmCompiledScriptFromJar,
    private val evaluationConfiguration: ScriptEvaluationConfiguration,
) : CompiledScript {
    private val evaluator = HollowEngineScriptEvaluator()
    private val scriptClass = lazy {
        runBlocking {
            (script.getClass(evaluationConfiguration) as ResultWithDiagnostics.Success).value
        }
    }

    override val type: KClass<*>
        get() = scriptClass.value

    override val implicitReceiverCount: Int
        get() {
            scriptClass.value
            return script.compilationConfiguration[ScriptCompilationConfiguration.implicitReceivers]?.size ?: 0
        }

    override val isClientSide: Boolean
        get() = script.metadata(evaluationConfiguration)
            .compilationConfiguration[ScriptCompilationConfiguration.isClientSideScript] == true

    override fun <T> execute(body: ScriptEvaluationConfiguration.Builder.() -> Unit): Result<T> {
        val result = try {
            runBlocking { evaluator(script, evaluationConfiguration.with(body)) }
        } catch (e: LinkageError) {
            return Result.failure(e)
        }

        if (result !is ResultWithDiagnostics.Success) {
            return Result.failure(ScriptEvaluationException(name, result.reports.map { it.convert() }))
        }
        val value = result.value.returnValue
        if (value is ResultValue.Error) return Result.failure(value.error)

        @Suppress("UNCHECKED_CAST") return Result.success(value.scriptInstance as T)
    }
}

internal val JvmScriptEvaluationConfigurationKeys.actualClassLoader by PropertiesCollection.key<ClassLoader?>(
    isTransient = true
)

/** Whether this compiled script opted into instance sharing with `@file:SharedScript`. */
val ScriptCompilationConfigurationKeys.isSharedScript by PropertiesCollection.key<Boolean>()

/** Whether this compiled script declared `@file:ClientSide`. */
val ScriptCompilationConfigurationKeys.isClientSideScript by PropertiesCollection.key<Boolean>()

open class HollowEngineScriptEvaluator : ScriptEvaluator {
    companion object {
        private val constructorCache: MutableMap<Class<*>, MethodHandle> = Collections.synchronizedMap(WeakHashMap())
    }

    override suspend operator fun invoke(
        compiledScript: KotlinCompiledScript,
        scriptEvaluationConfiguration: ScriptEvaluationConfiguration,
    ): ResultWithDiagnostics<EvaluationResult> = try {
        compiledScript.getClass(scriptEvaluationConfiguration).onSuccess evaluation@{ scriptClass ->
            val sharedConfiguration = scriptEvaluationConfiguration.getOrPrepareShared(scriptClass.java.classLoader)
            val configurationForOtherScripts by lazy {
                sharedConfiguration.with {
                    reset(ScriptEvaluationConfiguration.previousSnippets)
                    reset(ScriptEvaluationConfiguration.constructorArgs)
                }
            }
            val canShareInstance =
                compiledScript.compilationConfiguration[ScriptCompilationConfiguration.isSharedScript] == true

            if (canShareInstance) {
                SharedScriptClasses.instanceOf(scriptClass)?.asSuccess()?.let { return@evaluation it }
            }

            compiledScript.otherScripts.mapSuccess {
                invoke(it, configurationForOtherScripts)
            }.onSuccess { importedScriptsEvalResults ->
                importedScriptsEvalResults.firstOrNull { it.returnValue is ResultValue.Error }?.let {
                    return@onSuccess it.asSuccess()
                }

                val refinedEvalConfiguration = sharedConfiguration.with {
                    compilationConfiguration(compiledScript.compilationConfiguration)
                }.refineBeforeEvaluation(compiledScript).valueOr {
                    return@invoke ResultWithDiagnostics.Failure(it.reports)
                }

                val resultValue = try {
                    val instance = scriptClass.evalWithConfigAndOtherScriptsResults(
                        refinedEvalConfiguration, importedScriptsEvalResults
                    )

                    compiledScript.resultField?.let { (resultFieldName, resultType) ->
                        scriptClass.java.declaredFields.find { it.name == resultFieldName }?.let {
                            it.isAccessible = true
                            ResultValue.Value(
                                resultFieldName, it.get(instance), resultType.typeName, scriptClass, instance
                            )
                        } ?: ResultValue.Unit(scriptClass, instance)
                    } ?: ResultValue.Unit(scriptClass, instance)

                } catch (e: InvocationTargetException) {
                    ResultValue.Error(e.targetException ?: e, e, scriptClass)
                } catch (e: Throwable) {
                    // Ловим исключения от MethodHandle
                    ResultValue.Error(e, e, scriptClass)
                }

                EvaluationResult(resultValue, refinedEvalConfiguration).let { result ->
                    if (canShareInstance && resultValue !is ResultValue.Error) {
                        SharedScriptClasses.rememberInstance(scriptClass, result)
                    }
                    ResultWithDiagnostics.Success(result)
                }
            }
        }
    } catch (e: LinkageError) {
        throw e
    } catch (e: Throwable) {
        ResultWithDiagnostics.Failure(
            e.asDiagnostics(path = compiledScript.sourceLocationId)
        )
    }

    private fun KClass<*>.evalWithConfigAndOtherScriptsResults(
        refinedEvalConfiguration: ScriptEvaluationConfiguration,
        importedScriptsEvalResults: List<EvaluationResult>,
    ): Any {
        val isCompiledWithK2 = refinedEvalConfiguration[ScriptEvaluationConfiguration.compilationConfiguration]?.get(
                ScriptCompilationConfiguration._languageVersion
            )?.let { it.substringBefore('.').toIntOrNull()?.let { ver -> ver >= 2 } } == true

        val providedProps = refinedEvalConfiguration[ScriptEvaluationConfiguration.providedProperties]
        val implicitReceivers = refinedEvalConfiguration[ScriptEvaluationConfiguration.implicitReceivers]
        val expectedImplicitReceiverCount =
            refinedEvalConfiguration[ScriptEvaluationConfiguration.compilationConfiguration]?.get(
                    ScriptCompilationConfiguration.implicitReceivers
                )?.size ?: 0
        val ctorArgs = refinedEvalConfiguration[ScriptEvaluationConfiguration.constructorArgs]
        val prevSnippets = refinedEvalConfiguration[ScriptEvaluationConfiguration.previousSnippets]

        var estimatedSize = importedScriptsEvalResults.size
        if (prevSnippets != null) estimatedSize++
        if (ctorArgs != null) estimatedSize += ctorArgs.size
        if (implicitReceivers != null) estimatedSize += minOf(implicitReceivers.size, expectedImplicitReceiverCount)
        if (providedProps != null) estimatedSize += providedProps.size * (if (isCompiledWithK2) 2 else 1)

        val args = ArrayList<Any?>(estimatedSize)

        prevSnippets?.let { args.add(it.toTypedArray()) }
        ctorArgs?.let { args.addAll(it) }

        if (isCompiledWithK2) {
            providedProps?.forEach { args.add(it.value) }
        }

        importedScriptsEvalResults.forEach {
            args.add(it.returnValue.scriptInstance)
        }

        implicitReceivers?.take(expectedImplicitReceiverCount)?.let { args.addAll(it) }

        if (!isCompiledWithK2) {
            providedProps?.forEach { args.add(it.value) }
        }

        @Suppress("UNCHECKED_CAST") val wrapper: ScriptExecutionWrapper<Any>? =
            refinedEvalConfiguration[ScriptEvaluationConfiguration.scriptExecutionWrapper] as ScriptExecutionWrapper<Any>?

        val saveClassLoader = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = this.java.classLoader
        return try {
            val constructorHandle = constructorCache.computeIfAbsent(this.java) { clazz ->
                val ctor = clazz.constructors.single()
                ctor.isAccessible = true
                MethodHandles.publicLookup().unreflectConstructor(ctor)
            }

            wrapper?.invoke { constructorHandle.invokeWithArguments(args) } ?: constructorHandle.invokeWithArguments(
                args
            )
        } finally {
            Thread.currentThread().contextClassLoader = saveClassLoader
        }
    }
}

private fun ScriptEvaluationConfiguration.getOrPrepareShared(classLoader: ClassLoader): ScriptEvaluationConfiguration =
    if (this[ScriptEvaluationConfiguration.jvm.actualClassLoader] != null) this
    else with {
        ScriptEvaluationConfiguration.jvm.actualClassLoader(classLoader)
    }

internal class KJvmCompiledScriptFromJar(
    private val scriptClassName: String,
    private val file: File,
) : KotlinCompiledScript {
    private var loadedScript: KJvmCompiledScript? = null

    private fun getScriptOrFail(): KJvmCompiledScript =
        loadedScript ?: throw IllegalStateException("Compiled script is not loaded yet")

    override suspend fun getClass(scriptEvaluationConfiguration: ScriptEvaluationConfiguration?): ResultWithDiagnostics<KClass<*>> =
        metadata(scriptEvaluationConfiguration).getClass(scriptEvaluationConfiguration)

    /**
     * The serialized description of the script, read without defining the script class.
     */
    fun metadata(scriptEvaluationConfiguration: ScriptEvaluationConfiguration?): KJvmCompiledScript {
        loadedScript?.let { return it }

        val actualEvaluationConfiguration = scriptEvaluationConfiguration ?: ScriptEvaluationConfiguration()
        val baseClassLoader = actualEvaluationConfiguration[ScriptEvaluationConfiguration.jvm.baseClassLoader]
            ?: Thread.currentThread().contextClassLoader
        val entries = readJarEntries()
        val classLoader = MemoryClassLoader(entries, baseClassLoader)
        val script = createScriptFromClassLoader(scriptClassName, classLoader)
        classLoader.shareClassesOf(
            SharedScriptClasses.loadersFor(
                script.otherScripts,
                entries,
                baseClassLoader,
                ScriptCache.sharedScriptsOf(file),
            ),
        )
        loadedScript = script
        return script
    }

    override val compilationConfiguration: ScriptCompilationConfiguration
        get() = getScriptOrFail().compilationConfiguration

    override val sourceLocationId: String?
        get() = loadedScript?.sourceLocationId

    override val otherScripts: List<KotlinCompiledScript>
        get() = getScriptOrFail().otherScripts

    override val resultField
        get() = getScriptOrFail().resultField

    private fun readJarEntries(): Map<String, ByteArray> = JarFile(file).use { jar ->
        jar.entries().asSequence().associate { it.name to jar.getInputStream(it).readBytes() }
    }
}

class MemoryClassLoader(
    private val resources: Map<String, ByteArray>,
    parent: ClassLoader?,
) : ClassLoader(parent) {
    /** Classes of shared scripts, which this loader must link against instead of defining its own. */
    private var shared: Map<String, ClassLoader> = emptyMap()

    fun shareClassesOf(loaders: Map<String, ClassLoader>) {
        shared = loaders
    }

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
        val resource = name.replace('.', '/') + ".class"

        return resources[resource]?.let { bytes ->
            val protectionDomain = ProtectionDomain(null, null)
            defineClass(name, bytes, 0, bytes.size, protectionDomain)
        } ?: throw ClassNotFoundException(name)
    }

    override fun getResourceAsStream(name: String): InputStream? = resources[name]?.inputStream()
}

fun ScriptDiagnostic.convert(): Diagnostic {
    return Diagnostic(location?.let {
        Range(
            Position(it.start.line, it.start.col),
            Position(it.end?.line ?: it.start.line, it.end?.col ?: it.start.col)
        )
    } ?: Range(Position(-1, -1), Position(-1, -1)), when (severity) {
        ScriptDiagnostic.Severity.DEBUG -> Severity.DEBUG
        ScriptDiagnostic.Severity.INFO -> Severity.INFO
        ScriptDiagnostic.Severity.WARNING -> Severity.WARNING
        ScriptDiagnostic.Severity.ERROR -> Severity.ERROR
        ScriptDiagnostic.Severity.FATAL -> Severity.FATAL
    }, message)
}
