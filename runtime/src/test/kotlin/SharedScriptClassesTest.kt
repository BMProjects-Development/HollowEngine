import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache.SharedScriptRef
import ru.hollowhorizon.hollowengine.common.scripting.compiling.SharedScriptClasses
import ru.hollowhorizon.hollowengine.common.scripting.compiling.isSharedScript
import ru.hollowhorizon.hollowengine.common.scripting.compiling.sharedScriptFingerprint
import ru.hollowhorizon.hollowengine.common.scripting.compiling.sharedScriptId
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class SharedScriptClassesTest {
    @Test
    fun `an unresolved shared identity fails instead of loading a private copy`() {
        val script = sharedScript("UnregisteredShared")
        val failure = assertFailsWith<IllegalStateException> {
            SharedScriptClasses.loadersFor(listOf(script), emptyMap(), javaClass.classLoader)
        }
        assertTrue(failure.message.orEmpty().contains("Cannot resolve shared script identity"))
        assertTrue(failure.message.orEmpty().contains("UnregisteredShared"))
    }

    @Test
    fun `equal generated class names in separate import graphs keep independent namespaces`() {
        val script = sharedScript("IndependentShared")
        fun load(namespace: String): ClassLoader {
            val reference = SharedScriptRef("IndependentShared", ScriptId(namespace, "common.kts"), "revision")
            return SharedScriptClasses.loadersFor(
                listOf(script), mapOf("IndependentShared.class" to byteArrayOf()), javaClass.classLoader,
                mapOf("IndependentShared" to reference),
            ).getValue("IndependentShared")
        }
        assertNotSame(load("independent-one"), load("independent-two"))
    }

    @Test
    fun `different script identities cannot claim one generated name in the same import graph`() {
        fun script(namespace: String) = KJvmCompiledScript(
            null, ScriptCompilationConfiguration {
                isSharedScript(true)
                sharedScriptId("$namespace:common.kts")
                sharedScriptFingerprint("revision")
            }, "ConflictingShared", null, emptyList(), null,
        )
        val failure = assertFailsWith<IllegalStateException> {
            SharedScriptClasses.loadersFor(
                listOf(script("conflict-one"), script("conflict-two")),
                mapOf("ConflictingShared.class" to byteArrayOf()), javaClass.classLoader,
            )
        }
        assertTrue(failure.message.orEmpty().contains("conflicting identities in one import graph"))
    }

    @Test
    fun `serialized shared identity must match the jar manifest`() {
        val script = KJvmCompiledScript(
            null, ScriptCompilationConfiguration {
                isSharedScript(true)
                sharedScriptId("conflicting-metadata:common.kts")
                sharedScriptFingerprint("compiled-revision")
            }, "MismatchedShared", null, emptyList(), null,
        )
        val failure = assertFailsWith<IllegalStateException> {
            SharedScriptClasses.loadersFor(
                listOf(script), emptyMap(), javaClass.classLoader,
                mapOf("MismatchedShared" to SharedScriptRef(
                    "MismatchedShared", ScriptId("conflicting-metadata", "common.kts"), "manifest-revision",
                )),
            )
        }
        assertTrue(failure.message.orEmpty().contains("Conflicting shared script metadata"))
    }

    private fun sharedScript(name: String) = KJvmCompiledScript(
        null, ScriptCompilationConfiguration { isSharedScript(true) }, name, null, emptyList(), null,
    )
}
