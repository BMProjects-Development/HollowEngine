import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import org.koin.core.Koin
import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentInitializerImpl
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonContext
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonDescriptor
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonHostServices
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonsChangedEvent
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.ScriptDefaultImportsEvent
import ru.hollowhorizon.hollowengine.common.scripting.ScriptDefinitionsEvent
import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironmentInitializer
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import java.io.File
import java.util.jar.JarOutputStream
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

abstract class LifecycleScript

class ScriptDefinitionsLifecycleTest {
    @Test
    fun `compiler waits for addon batch and refreshes late definitions without remapping`(@TempDir directory: File) = runBlocking {
        val scope = CoroutineScope(Job())
        val addonScope = CoroutineScope(Job())
        val classpath = System.getProperty("java.class.path").split(File.pathSeparator).map(::File).filter(File::exists)
        var preparations = 0
        val initializer = ScriptingEnvironmentInitializerImpl(
            prepareClasspath = { preparations++; Mappings.EMPTY to classpath },
            definitions = {
                val providers = mutableListOf(ScriptClassProvider("kts", LifecycleScript::class.qualifiedName!!))
                ScriptDefinitionsEvent.post(ScriptDefinitionsEvent(providers))
                providers.map { provider ->
                    val imports = provider.defaultImports.toMutableList()
                    ScriptDefaultImportsEvent.post(ScriptDefaultImportsEvent(provider.extension, imports))
                    provider.copy(defaultImports = imports.distinct())
                }
            },
        )
        val compilerJar = directory.resolve("compiler.jar").also { JarOutputStream(it.outputStream()).close() }
        val services = TestHostServices()
        val context = HollowAddonContext(
            services, HollowAddonDescriptor("compiler", "test", null), compilerJar, javaClass.classLoader, Koin(),
        )
        try {
            ScriptingEnvironment.clear()
            initializer.load(context, scope)
            HollowAddonsChangedEvent.subscribe(scope) { initializer.onAddonsChanged(it) }
            assertEquals(0, preparations)
            assertFalse(ScriptingEnvironment.isAvailable())
            assertSame(initializer, services.findService(ScriptingEnvironmentInitializer::class))

            ScriptDefaultImportsEvent.subscribe(addonScope) { it.defaultImports += "java.time.Instant" }
            ScriptDefinitionsEvent.subscribe(addonScope) {
                it.providers += ScriptClassProvider("addon.kts", LifecycleScript::class.qualifiedName!!)
            }
            HollowAddonsChangedEvent.post(HollowAddonsChangedEvent())
            val first = ScriptingEnvironment.INSTANCE
            assertTrue(first.compiler.compile("first.addon.kts", "val value = Instant.EPOCH").isSuccess)
            assertTrue(first.analyzer.diagnostic("first.addon.kts", "val value = Instant.EPOCH").isEmpty())
            assertEquals(1, preparations)

            HollowAddonsChangedEvent.post(HollowAddonsChangedEvent())
            assertSame(first, ScriptingEnvironment.INSTANCE)

            addonScope.cancel()
            HollowAddonsChangedEvent.post(HollowAddonsChangedEvent())
            val refreshed = ScriptingEnvironment.INSTANCE
            assertNotSame(first, refreshed)
            assertEquals(1, preparations)
            assertTrue(refreshed.compiler.compile("second.kts", "val value = Instant.EPOCH").isFailure)
            assertTrue(refreshed.analyzer.diagnostic("second.kts", "val value = Instant.EPOCH").isNotEmpty())
        } finally {
            scope.cancel()
            addonScope.cancel()
            initializer.unload(context)
            File("hollowengine").deleteRecursively()
        }
    }

    private class TestHostServices : HollowAddonHostServices {
        private val services = HashMap<KClass<*>, Any>()
        override fun log(message: String) = Unit
        override fun <T : Any> publishService(type: KClass<T>, instance: T) { services[type] = instance }
        override fun <T : Any> unpublishService(type: KClass<T>, instance: T) { services.remove(type, instance) }
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> findService(type: KClass<T>): T? = services[type] as T?
        override fun <T : Any> findServices(type: KClass<T>): List<T> = listOfNotNull(findService(type))
    }
}
