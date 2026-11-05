import kotlin.test.Test
import kotlin.test.assertTrue

/** A class declared at the top of a script can be named in the signatures of that script, not only in bodies. */
class ScriptClassSignatureTest : StageTest() {
    private fun assertCompiles(name: String, code: String) {
        val result = compiler.compileAs("$name.stage.kts", code)
        assertTrue(result.isSuccess, "Expected '$name' to compile: ${result.exceptionOrNull()}")
    }

    @Test
    fun `a parameter is typed by a class of the script`() =
        assertCompiles("param", "data class P(val a: String)\nfun f(p: P) = p.a")

    @Test
    fun `a function returns a class of the script`() =
        assertCompiles("result", "data class P(val a: String)\nfun f(): P = P(\"x\")")

    @Test
    fun `a property is typed by a class of the script`() =
        assertCompiles("property", "class P(val a: String)\nval v: P = P(\"x\")")

    @Test
    fun `a stage returns a class of the script`() =
        assertCompiles("stage", "@kotlinx.serialization.Serializable\ndata class P(val a: String)\n@State\nsuspend fun f(): P = P(\"x\")")
}
