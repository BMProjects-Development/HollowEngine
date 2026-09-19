import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinSpecLayout
import ru.hollowhorizon.hollowengine.common.ScriptingEnvironmentImpl
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider
import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.compiling.CompiledScript
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinScript
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.script.experimental.api.constructorArgs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

open class MixinFixtureBase {
    open fun inherited() = Unit
}

class MixinFixture : MixinFixtureBase() {
    var ticks = 0
    private var bonus = 0

    fun tick() {
        ticks += helper(bonus)
    }

    fun isActive(): Boolean = ticks > 0

    fun overloaded(value: Int) = Unit

    fun overloaded(value: String) = Unit

    private fun helper(value: Int): Int = value + 1
}

class MixinScriptCompilationTest {
    private lateinit var environment: ScriptingEnvironmentImpl

    @BeforeTest
    fun setUp() {
        environment = ScriptingEnvironmentImpl(
            javaHome = File(System.getProperty("java.home")),
            classpath = testClasspath(),
            scriptTypes = listOf(
                ScriptClassProvider(
                    extension = ".mixin.kts",
                    baseClass = MixinScript::class.qualifiedName!!,
                    defaultImports = listOf(
                        "ru.hollowhorizon.hollowengine.common.scripting.mixins.*",
                        "ru.hollowhorizon.hollowengine.common.scripting.mixins.Point.*",
                    ),
                ),
            ),
            mappings = Mappings.EMPTY,
        )
        ScriptingEnvironment.INSTANCE = environment
    }

    @AfterTest
    fun tearDown() {
        ScriptingEnvironment.clear()
    }

    @Test
    fun `the spec resolves every declaration and names the keys the script registers its bodies under`() {
        val compiled = compile(
            """
            mixin<MixinFixture> {
                inject("tick") {
                    at(HEAD)
                    code { }
                }
                modifyReturnValue<Boolean>("isActive") {
                    code { original -> !original }
                }
                method("tick") {
                    afterCall<MixinFixture>("helper") { }
                    modifyCall<MixinFixture, Int>("helper") { original -> original * 10 }
                    replaceCall<MixinFixture>("helper") { instance, args -> proceed() }
                    wrap { proceed() }
                    before { }
                }
                modifyExpressionValue<Int>("tick") {
                    at(field<MixinFixture>("bonus"))
                    code { original -> original }
                }
            }
            """,
        )

        val handlers = specHandlers(compiled)
        val script = compiled.execute<MixinScript> { constructorArgs("test:fixture.mixin.kts") }.getOrThrow()
        assertEquals(handlers.map { it.key }.toSet(), script.handlers.keys)

        val byKind = handlers.groupBy { it.kind }
        assertEquals(List(3) { "MixinFixture.tick()V" }, byKind.getValue("INJECT").map { it.target })
        assertEquals("MixinFixture.isActive()Z", byKind.getValue("MODIFY_RETURN_VALUE").single().target)

        val afterHelper = handlers.single { it.kind == "INJECT" && it.point == "INVOKE" }
        assertEquals("MixinFixture.helper(I)I", afterHelper.member)
        assertEquals("AFTER", afterHelper.shift)

        val expressions = byKind.getValue("MODIFY_EXPRESSION_VALUE").associate { it.point to it.member }
        assertEquals("MixinFixture.helper(I)I", expressions["INVOKE"])
        assertEquals("MixinFixture.bonus:I", expressions["FIELD"])

        // The body registered under a key is the one declared there: this one negates the return value.
        val negate = script.handlers.getValue(byKind.getValue("MODIFY_RETURN_VALUE").single().key)
        assertEquals(false, negate.handle(MixinFixture(), emptyArray(), true))
    }

    @Test
    fun `identical declarations get distinct keys that survive recompilation`() {
        val source = """
            mixin<MixinFixture> {
                method("tick") {
                    before { }
                    before { }
                }
            }
            """
        val first = specHandlers(compile(source)).map { it.key }
        val second = specHandlers(compile(source)).map { it.key }
        assertEquals(2, first.toSet().size)
        assertEquals(first, second)
    }

    @Test
    fun `compiling early for the spec alone gives the spec a regular compilation gives`() {
        val source = """mixin<MixinFixture> { method("tick") { before { } } }"""
        val directory = createTempDirectory("mixin-spec").toFile()
        try {
            val file = directory.resolve("early.mixin.kts").apply { writeText(source) }
            val early = environment.compiler.compileMixinSpec(file, emptyList()).getOrThrow()
                ?: fail("The script declares mixins, so it has a spec")
            val regular = environment.compiler.compile("early.mixin.kts", source).getOrThrow()
            assertEquals(specHandlers(regular).map { it.key }, specHandlers(early).map { it.key })

            val plain = directory.resolve("plain.mixin.kts").apply { writeText("val unused = 1") }
            assertEquals(null, environment.compiler.compileMixinSpec(plain, emptyList()).getOrThrow())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `a declaration the classes cannot satisfy is a compilation error in the script`() {
        assertFails("has no method 'missing'", """mixin<MixinFixture> { method("missing") { before { } } }""")
        assertFails("inherited from MixinFixtureBase", """mixin<MixinFixture> { method("inherited") { before { } } }""")
        assertFails("\"overloaded(I)V\"", """mixin<MixinFixture> { method("overloaded") { before { } } }""")
        assertFails("never calls MixinFixture.isActive", """mixin<MixinFixture> { method("tick") { afterCall<MixinFixture>("isActive") { } } }""")
        assertFails("the value is kotlin.Boolean, not kotlin.Int", """mixin<MixinFixture> { method("isActive") { returns<Int> { it } } }""")
        assertFails("must be a string constant", """val name = "tick"; mixin<MixinFixture> { method(name) { before { } } }""")
    }

    private fun compile(code: String): CompiledScript =
        environment.compiler.compile("fixture.mixin.kts", code.trimIndent()).getOrThrow()

    private fun assertFails(expected: String, code: String) {
        val error = environment.compiler.compile("broken.mixin.kts", code).exceptionOrNull()
            ?: fail("Expected '$code' not to compile")
        assertTrue(expected in error.toString(), "Expected '$expected' in: $error")
    }

    private class SpecHandler(
        val key: String,
        val kind: String,
        val point: String,
        val shift: String,
        val target: String,
        val member: String?,
    )

    private fun specHandlers(compiled: CompiledScript): List<SpecHandler> {
        val type = compiled.type.java
        val bytes = type.classLoader.getResourceAsStream(type.name.replace('.', '/') + MixinSpecLayout.CLASS_SUFFIX + ".class")
            ?.use { it.readBytes() } ?: fail("The script has no mixin spec")
        return specHandlers(bytes)
    }

    private fun specHandlers(bytes: ByteArray): List<SpecHandler> {
        val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
        return node.methods.mapNotNull { method ->
            val annotation = method.invisibleAnnotations?.firstOrNull { it.desc == MixinSpecLayout.HANDLER_ANNOTATION }
                ?: return@mapNotNull null
            val members = method.instructions.mapNotNull { instruction ->
                when (instruction) {
                    is MethodInsnNode -> "${instruction.owner}.${instruction.name}${instruction.desc}"
                    is FieldInsnNode -> "${instruction.owner}.${instruction.name}:${instruction.desc}"
                    else -> null
                }
            }
            val values = annotation.values()
            SpecHandler(
                key = values.getValue("key") as String,
                kind = values.getValue("kind") as String,
                point = values.getValue("point") as String,
                shift = values.getValue("shift") as String,
                target = members.first(),
                member = members.getOrNull(1),
            )
        }
    }

    private fun AnnotationNode.values(): Map<String, Any> =
        values.chunked(2).associate { (name, value) -> name as String to value }

    private fun testClasspath(): List<File> = System.getProperty("java.class.path")
        .split(File.pathSeparator)
        .asSequence()
        .filter(String::isNotBlank)
        .map(::File)
        .filter(File::exists)
        .distinctBy { it.absoluteFile.normalize() }
        .toList()
}
