package ru.hollowhorizon.hollowengine.client.shadergraph

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShaderGraphCompilerTest {
    private val output = ShaderGraphNode("out", ShaderNodeLibrary.SURFACE_OUTPUT)

    private fun graph(vararg nodes: ShaderGraphNode, links: List<ShaderGraphLink>) =
        ShaderGraph(nodes = nodes.toList() + output, links = links)

    @Test
    fun `the default graph draws what a material without one draws`() {
        val code = ShaderGraphCompiler.compile(ShaderNodeLibrary.defaultSurface())

        assertEquals(emptyList(), code.diagnostics)
        assertTrue(code.fragment.text.contains("texture(Sampler0, sg_texture_uv)"))
        assertTrue(code.fragment.text.contains("vec4 sg_1_RGBA = sg_color;"), code.fragment.text)
        // A vec4 into the vec3 color is cut down to its first three components.
        assertTrue(code.outputs.getValue(SurfaceOutputs.COLOR).endsWith(".xyz"))
        assertTrue(code.vertex.statements.isEmpty())
    }

    @Test
    fun `a dynamic node takes the widest input and spreads a number over it`() {
        val graph = graph(
            ShaderGraphNode("v", "hollowengine:input/vector3", values = mapOf("Value" to listOf(1f, 2f, 3f))),
            ShaderGraphNode("m", "hollowengine:math/multiply", values = mapOf("B" to listOf(0.5f))),
            links = listOf(ShaderGraphLink("v", "Out", "m", "A"), ShaderGraphLink("m", "Out", "out", SurfaceOutputs.COLOR)),
        )
        val code = ShaderGraphCompiler.compile(graph)

        assertEquals(ShaderType.VEC3, code.types.output("m", "Out"))
        assertTrue(code.fragment.text.contains("vec3 sg_1_Out = sg_0_Out * vec3(0.5);"), code.fragment.text)
    }

    @Test
    fun `what only the vertex offset needs runs per vertex only`() {
        val graph = graph(
            ShaderGraphNode("time", "hollowengine:input/time"),
            ShaderGraphNode("wave", "hollowengine:math/sine"),
            links = listOf(
                ShaderGraphLink("time", "Time", "wave", "In"),
                ShaderGraphLink("wave", "Out", "out", SurfaceOutputs.VERTEX_OFFSET),
            ),
        )
        val code = ShaderGraphCompiler.compile(graph)

        assertTrue(code.vertex.text.contains("sin("))
        assertFalse(code.fragment.text.contains("sin("))
        assertEquals("vec3(sg_1_Out)", code.outputs.getValue(SurfaceOutputs.VERTEX_OFFSET))
    }

    @Test
    fun `reading the scene per vertex is reported`() {
        val graph = graph(
            ShaderGraphNode("depth", "hollowengine:input/scene_depth"),
            links = listOf(ShaderGraphLink("depth", "Difference", "out", SurfaceOutputs.VERTEX_OFFSET)),
        )
        val problems = ShaderGraphCompiler.compile(graph).diagnostics.map { it.problem }

        assertTrue(ShaderProblem.FRAGMENT_ONLY_IN_VERTEX in problems)
    }

    @Test
    fun `a cycle is reported and its nodes are left out`() {
        val graph = graph(
            ShaderGraphNode("a", "hollowengine:math/add"),
            ShaderGraphNode("b", "hollowengine:math/add"),
            links = listOf(
                ShaderGraphLink("a", "Out", "b", "A"),
                ShaderGraphLink("b", "Out", "a", "A"),
                ShaderGraphLink("b", "Out", "out", SurfaceOutputs.ALPHA),
            ),
        )
        val code = ShaderGraphCompiler.compile(graph)

        assertEquals(setOf("a", "b"), code.diagnostics.filter { it.problem == ShaderProblem.CYCLE }.map { it.node }.toSet())
        assertEquals("1.0", code.outputs.getValue(SurfaceOutputs.ALPHA))
    }

    @Test
    fun `a reroute carries whatever it is given along, a texture too`() {
        val graph = ShaderGraph(
            nodes = listOf(
                ShaderGraphNode("noise", ShaderNodeLibrary.PROPERTY, options = mapOf("property" to "Noise")),
                ShaderGraphNode("carry", ShaderNodeLibrary.REROUTE),
                ShaderGraphNode("sample", "hollowengine:texture/sample"),
                ShaderGraphNode("color", "hollowengine:input/vertex_color"),
                ShaderGraphNode("pass", ShaderNodeLibrary.REROUTE),
                output,
            ),
            links = listOf(
                ShaderGraphLink("noise", "Out", "carry", ShaderNodeLibrary.REROUTE_INPUT),
                ShaderGraphLink("carry", ShaderNodeLibrary.REROUTE_OUTPUT, "sample", "Texture"),
                ShaderGraphLink("sample", "RGB", "out", SurfaceOutputs.COLOR),
                ShaderGraphLink("color", "A", "pass", ShaderNodeLibrary.REROUTE_INPUT),
                ShaderGraphLink("pass", ShaderNodeLibrary.REROUTE_OUTPUT, "out", SurfaceOutputs.ALPHA),
            ),
            properties = listOf(ShaderGraphProperty("Noise", ShaderType.TEXTURE)),
        )
        val code = ShaderGraphCompiler.compile(graph)

        assertEquals(emptyList(), code.diagnostics)
        assertEquals(ShaderType.TEXTURE, code.types.output("carry", ShaderNodeLibrary.REROUTE_OUTPUT))
        assertEquals(ShaderType.FLOAT, code.types.output("pass", ShaderNodeLibrary.REROUTE_OUTPUT))
        assertTrue(code.fragment.text.contains("texture(p_Noise, sg_texture_uv)"), code.fragment.text)
    }

    @Test
    fun `a texture property is sampled by its uniform`() {
        val graph = ShaderGraph(
            nodes = listOf(
                ShaderGraphNode("noise", ShaderNodeLibrary.PROPERTY, options = mapOf("property" to "Noise")),
                ShaderGraphNode("sample", "hollowengine:texture/sample"),
                output,
            ),
            links = listOf(
                ShaderGraphLink("noise", "Out", "sample", "Texture"),
                ShaderGraphLink("sample", "RGB", "out", SurfaceOutputs.COLOR),
            ),
            properties = listOf(ShaderGraphProperty("Noise", ShaderType.TEXTURE)),
        )
        val code = ShaderGraphCompiler.compile(graph)

        assertEquals(emptyList(), code.diagnostics)
        assertTrue(code.fragment.text.contains("texture(p_Noise, sg_texture_uv)"), code.fragment.text)
        assertTrue(ShaderGraphTemplates.surface(code, ShaderGraphTemplates.PARTICLE).fragment.contains("uniform sampler2D p_Noise;"))
    }

    @Test
    fun `the preview program numbers the nodes that show a preview, the output last`() {
        val preview = ShaderGraphCompiler.compilePreview(ShaderNodeLibrary.defaultSurface())

        // The particle color and the split start with their previews off.
        assertEquals(listOf("texture", "tint", "output"), preview.previewIndex.keys.toList())
        assertTrue(preview.selection.contains("PreviewNode == 2) fragColor = sg_surface("), preview.selection)
        assertTrue(ShaderGraphTemplates.preview(preview).contains("uniform int PreviewNode;"))
    }

    @Test
    fun `a value typed on a node is a uniform of the preview, so changing it changes no code`() {
        val graph = graph(
            ShaderGraphNode("m", "hollowengine:math/multiply", values = mapOf("B" to listOf(0.5f))),
            links = listOf(ShaderGraphLink("m", "Out", "out", SurfaceOutputs.ALPHA)),
        )
        val preview = ShaderGraphCompiler.compilePreview(graph)
        val changed = ShaderGraphCompiler.compilePreview(graph.withValue("m", "B", listOf(0.75f)))

        assertTrue(preview.statements.text.contains("PreviewValues["), preview.statements.text)
        assertEquals(ShaderGraphTemplates.preview(preview), ShaderGraphTemplates.preview(changed))
        assertTrue(ShaderGraphTemplates.preview(preview).contains("uniform vec4 PreviewValues[${preview.values.size}];"))
    }

    @Test
    fun `only what reads the normal, the view or the position is previewed on the mesh`() {
        val graph = graph(
            ShaderGraphNode("fresnel", "hollowengine:procedural/fresnel"),
            ShaderGraphNode("tint", "hollowengine:math/multiply"),
            ShaderGraphNode("noise", "hollowengine:procedural/value_noise"),
            links = listOf(ShaderGraphLink("fresnel", "Out", "tint", "A"), ShaderGraphLink("noise", "Out", "tint", "B")),
        )

        assertEquals(setOf("fresnel", "tint", "out"), ShaderGraphCompiler.compilePreview(graph).spatial)
    }

    @Test
    fun `a noise over a position is the 3D one`() {
        val graph = graph(
            ShaderGraphNode("local", "hollowengine:input/object_position"),
            ShaderGraphNode("flat", "hollowengine:procedural/gradient_noise"),
            ShaderGraphNode("solid", "hollowengine:procedural/gradient_noise"),
            ShaderGraphNode("sum", "hollowengine:math/add"),
            links = listOf(
                ShaderGraphLink("local", "Position", "solid", "UV"),
                ShaderGraphLink("flat", "Out", "sum", "A"),
                ShaderGraphLink("solid", "Out", "sum", "B"),
                ShaderGraphLink("sum", "Out", "out", SurfaceOutputs.ALPHA),
            ),
        )
        val code = ShaderGraphCompiler.compile(graph).fragment.text

        assertTrue(code.contains("sg_gradient_noise(sg_uv * 8.0)"), code)
        assertTrue(code.contains("sg_gradient_noise3(sg_0_Position * 8.0)"), code)
    }

    @Test
    fun `each stage gets only the functions it calls, so a derivative never reaches the vertex stage`() {
        val graph = graph(
            ShaderGraphNode("noise", "hollowengine:procedural/gradient_noise"),
            ShaderGraphNode("heave", "hollowengine:normal/offset"),
            ShaderGraphNode("bump", "hollowengine:normal/bump"),
            ShaderGraphNode("light", "hollowengine:normal/lambert"),
            links = listOf(
                ShaderGraphLink("noise", "Out", "heave", "Amount"),
                ShaderGraphLink("heave", "Offset", "out", SurfaceOutputs.VERTEX_OFFSET),
                ShaderGraphLink("noise", "Out", "bump", "Height"),
                ShaderGraphLink("bump", "Normal", "light", "Normal"),
                ShaderGraphLink("light", "Light", "out", SurfaceOutputs.ALPHA),
            ),
        )
        val code = ShaderGraphCompiler.compile(graph)

        assertTrue(ShaderLibrary.NORMALS in code.fragmentLibraries)
        assertFalse(ShaderLibrary.NORMALS in code.vertexLibraries)
        assertTrue(ShaderLibrary.GRADIENT_NOISE in code.vertexLibraries)
    }

    @Test
    fun `the output and every offset node move the mesh in their previews, unless the offset needs a fragment`() {
        fun displaced(kind: String, output: String) = ShaderGraphCompiler.compilePreview(
            graph(
                ShaderGraphNode("from", kind),
                links = listOf(ShaderGraphLink("from", output, "out", SurfaceOutputs.VERTEX_OFFSET)),
            )
        ).displaced

        assertEquals(setOf("from", "out"), displaced("hollowengine:normal/offset", "Offset"))
        assertEquals(emptySet(), displaced("hollowengine:normal/surface", "Normal"))
    }

    @Test
    fun `every post effect the engine ships compiles into the post template`() {
        val folder = File(requireNotNull(javaClass.getResource("/assets/hollowengine/materials/post")).toURI())
        val files = folder.listFiles { file -> file.name.endsWith(ShaderGraph.EXTENSION) }.orEmpty()
        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            val graph = ShaderGraphFormat.read(file.readText())
            val code = ShaderGraphCompiler.compile(graph)

            assertEquals(ShaderTarget.POST, graph.target, file.name)
            assertEquals(emptyList(), code.diagnostics, file.name)
            val fragment = ShaderGraphTemplates.post(code).fragment
            assertFalse(fragment.contains("//#"), file.name)
            graph.properties.forEach { assertTrue(fragment.contains(propertyUniform(it.name)), "${file.name}: ${it.name}") }
        }
    }

    @Test
    fun `what a post effect cannot read is reported`() {
        val graph = ShaderGraph(
            target = ShaderTarget.POST,
            nodes = listOf(
                ShaderGraphNode("color", "hollowengine:input/vertex_color"),
                ShaderGraphNode("out", ShaderNodeLibrary.POST_OUTPUT),
            ),
            links = listOf(ShaderGraphLink("color", "RGB", "out", PostOutputs.COLOR)),
        )
        val problems = ShaderGraphCompiler.compile(graph).diagnostics

        assertEquals(listOf(ShaderDiagnostic(ShaderProblem.UNAVAILABLE_INPUT, detail = ShaderInput.COLOR.name)), problems)
    }

    @Test
    fun `switching the target swaps the output node and keeps what both outputs take`() {
        val graph = graph(
            ShaderGraphNode("noise", "hollowengine:procedural/value_noise"),
            links = listOf(
                ShaderGraphLink("noise", "Out", "out", SurfaceOutputs.COLOR),
                ShaderGraphLink("noise", "Out", "out", SurfaceOutputs.ALPHA),
                ShaderGraphLink("noise", "Out", "out", SurfaceOutputs.VERTEX_OFFSET),
            ),
        ).withValue("out", SurfaceOutputs.EMISSION, listOf(1f))
        val post = graph.withTarget(ShaderTarget.POST)

        assertEquals(ShaderNodeLibrary.POST_OUTPUT, post.node("out")?.type)
        assertEquals(setOf(PostOutputs.COLOR, PostOutputs.ALPHA), post.links.map { it.input }.toSet())
        assertEquals(emptyMap(), post.node("out")?.values)
        assertEquals(emptyList(), ShaderGraphCompiler.compile(post).diagnostics)
        // A graph nobody touched becomes the default of the other target, not a surface graph with its output swapped.
        assertEquals(ShaderNodeLibrary.defaultPost(), ShaderNodeLibrary.defaultSurface().withTarget(ShaderTarget.POST))
    }

    @Test
    fun `a graph survives a round trip`() {
        val graph = ShaderNodeLibrary.defaultSurface().copy(properties = listOf(ShaderGraphProperty("Speed", default = listOf(2f))))
        assertEquals(graph, ShaderGraphFormat.read(ShaderGraphFormat.write(graph)))
    }

    @Test
    fun `a link from a node the graph does not have is pointed at`() {
        val code = ShaderGraphCompiler.compile(graph(links = listOf(ShaderGraphLink("missing", "Out", "out", SurfaceOutputs.COLOR))))

        assertEquals(listOf(ShaderDiagnostic(ShaderProblem.MISSING_NODE, "out", "missing")), code.diagnostics)
    }
}
