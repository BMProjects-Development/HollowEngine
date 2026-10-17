package ru.hollowhorizon.hollowengine.client.shadergraph

import kotlin.test.*

class ShaderExpressionTest {
    @Test
    fun `free names become pins, and the text reads them as floats`() {
        val expression = ShaderExpression.parse("(sin(x) + 1) / 2 * a.x + PI")

        assertNull(expression.error)
        assertEquals(listOf("x", "a"), expression.variables)
        assertEquals("(sin((X)) + 1.0) / 2.0 * (A).x + 3.14159265", expression.glsl { it.uppercase() })
    }

    @Test
    fun `nothing but an expression gets through`() {
        listOf("a; discard", "a = 1", "{ a }", "a // b", "#define a", "a /* b */").forEach { text ->
            assertNotNull(ShaderExpression.parse(text).error, text)
        }
        assertEquals(ShaderExpressionProblem.UNKNOWN_FUNCTION, ShaderExpression.parse("texture(a, b)").error?.problem)
        assertEquals(ShaderExpressionProblem.RESERVED_NAME, ShaderExpression.parse("gl_FragCoord.x").error?.problem)
        assertEquals(ShaderExpressionProblem.UNCLOSED, ShaderExpression.parse("sin(a").error?.problem)
        assertEquals(ShaderExpressionProblem.EXPECTED_VALUE, ShaderExpression.parse("a * ").error?.problem)
        // Spaced apart, the two minuses never read as a decrement.
        assertFalse("--" in ShaderExpression.parse("a - -b").glsl { "1.0" }.orEmpty())
    }

    @Test
    fun `an expression node has a pin per name, and a pin named like an engine input reads it`() {
        val node = ShaderGraphNode("e", ShaderNodeLibrary.EXPRESSION, options = mapOf("expression" to "uv.x * k"))
        val graph = ShaderGraph(
            nodes = listOf(node, ShaderGraphNode("out", ShaderNodeLibrary.SURFACE_OUTPUT)),
            links = listOf(ShaderGraphLink("e", "Out", "out", SurfaceOutputs.ALPHA)),
        )
        val code = ShaderGraphCompiler.compile(graph)

        assertEquals(listOf("uv", "k"), ShaderNodeTypes.of(node.type)!!.inputs(node).map { it.name })
        assertEquals(emptyList(), code.diagnostics)
        assertTrue(code.fragment.text.contains("(sg_uv).x * (0.0)"), code.fragment.text)
    }

    @Test
    fun `the type of an expression is read off its text`() {
        val widths = mapOf("v" to 3, "k" to 1)
        fun width(text: String) = ShaderExpression.parse(text).width { widths[it] }

        assertEquals(3, width("v * k + 1"))
        assertEquals(1, width("length(v) * k"))
        assertEquals(2, width("v.xy"))
        assertEquals(4, width("vec4(v, k)"))
        assertEquals(1, width("k > 0.5 ? 1.0 : 0.0"))
        assertNull(width("v * unknown"))
    }

    @Test
    fun `a wrong expression is reported on its node and compiles to zero`() {
        val node = ShaderGraphNode("e", ShaderNodeLibrary.EXPRESSION, options = mapOf("expression" to "sin(", "type" to "vec3"))
        val graph = ShaderGraph(
            nodes = listOf(node, ShaderGraphNode("out", ShaderNodeLibrary.SURFACE_OUTPUT)),
            links = listOf(ShaderGraphLink("e", "Out", "out", SurfaceOutputs.COLOR)),
        )
        val code = ShaderGraphCompiler.compile(graph)

        assertEquals(ShaderProblem.INVALID_EXPRESSION, code.diagnostics.single().problem)
        assertTrue(code.fragment.text.contains("vec3 sg_0_Out = vec3(0.0);"), code.fragment.text)
    }
}
