package ru.hollowhorizon.hollowengine.client.shadergraph

/** What can be wrong with a graph, for the editor to point at. */
enum class ShaderProblem {
    UNKNOWN_NODE,
    UNKNOWN_PIN,
    MISSING_NODE,
    NO_OUTPUT_NODE,
    SECOND_OUTPUT_NODE,
    CYCLE,
    TYPE_MISMATCH,
    UNRESOLVED_TYPE,
    FRAGMENT_ONLY_IN_VERTEX,
    UNAVAILABLE_INPUT,
    INVALID_EXPRESSION,
    GLSL_ERROR,
}

/** A problem, the node it is on, what it is about, and for some problems which of their kinds it is. */
data class ShaderDiagnostic(
    val problem: ShaderProblem,
    val node: String? = null,
    val detail: String = "",
    val reason: String? = null,
)

/** The GLSL of one stage: statements in order, each declaring the variables of one node's outputs. */
class ShaderStageCode(val statements: List<String>) {
    val text: String get() = statements.joinToString("\n")
}

/**
 * A graph compiled for one place it runs: the code of each stage, what each input of the output node
 * is, and everything the shader around it has to declare.
 */
class ShaderGraphCode(
    /** The shared functions each stage calls; a fragment-only one, such as a derivative, never goes in the vertex stage. */
    val vertexLibraries: List<ShaderLibrary>,
    val fragmentLibraries: List<ShaderLibrary>,
    val properties: List<ShaderGraphProperty>,
    val vertex: ShaderStageCode,
    val fragment: ShaderStageCode,
    val outputs: Map<String, String>,
    val linkedOutputs: Set<String>,
    val inputs: Set<ShaderInput>,
    val diagnostics: List<ShaderDiagnostic>,
    val types: ShaderGraphTypes,
)

/** A value typed on a node that the preview program reads from `PreviewValues[index]`. */
class ShaderValueSlot(val node: String, val pin: String, val width: Int)

/**
 * The whole graph as one fragment program, for the previews: every node is computed, and the uniform
 * `PreviewNode` picks whose value is drawn, as [previewIndex] numbers them.
 */
class ShaderPreviewCode(
    val libraries: List<ShaderLibrary>,
    val vertexLibraries: List<ShaderLibrary>,
    val vertex: ShaderStageCode,
    val vertexSelection: String,
    val displaced: Set<String>,
    val properties: List<ShaderGraphProperty>,
    val statements: ShaderStageCode,
    val statementNodes: List<String>,
    val previewIndex: Map<String, Int>,
    val spatial: Set<String>,
    val values: List<ShaderValueSlot>,
    val selection: String,
    val diagnostics: List<ShaderDiagnostic>,
)

/** The type every pin of a graph resolved to, for the editor to color links and check new ones. */
class ShaderGraphTypes(private val outputs: Map<Pair<String, String>, ShaderType>, private val inputs: Map<Pair<String, String>, ShaderType>) {
    fun output(node: String, pin: String): ShaderType? = outputs[node to pin]
    fun input(node: String, pin: String): ShaderType? = inputs[node to pin]
}

/**
 * Turns a graph into GLSL.
 *
 * Nodes are pure: each output is one expression over the inputs, written to a variable of its own.
 * A node runs in the vertex stage when an input of the output node listed as a vertex input depends
 * on it, and in the fragment stage when any other does; a node both depend on is computed in both.
 */
object ShaderGraphCompiler {
    /** Compiles [graph] for a place that offers [available] of the engine inputs, by default what its target offers. */
    fun compile(graph: ShaderGraph, available: Set<ShaderInput> = graph.target.inputs): ShaderGraphCode {
        val resolved = ResolvedGraph(graph, values = null)
        val diagnostics = resolved.diagnostics
        val master = resolved.master
        if (master == null) {
            return ShaderGraphCode(
                emptyList(), emptyList(), graph.properties, ShaderStageCode(emptyList()), ShaderStageCode(emptyList()),
                emptyMap(), emptySet(), emptySet(), diagnostics, resolved.types(),
            )
        }

        val masterType = resolved.typeOf(master)
        val masterInputs = masterType.inputs(master)
        val vertexOutputs = masterType.master?.vertexInputs.orEmpty()
        val linked = masterInputs.filter { graph.linkInto(master.id, it.name) != null }.map { it.name }.toSet()
        val vertexRoots = linked.filter { it in vertexOutputs }.mapNotNull { graph.linkInto(master.id, it)?.from }
        val fragmentRoots = linked.filter { it !in vertexOutputs }.mapNotNull { graph.linkInto(master.id, it)?.from }

        val vertex = resolved.emit(resolved.reachableFrom(vertexRoots), vertex = true)
        val fragment = resolved.emit(resolved.reachableFrom(fragmentRoots), vertex = false)
        val used = vertex.inputs + fragment.inputs
        (used - available).forEach { diagnostics += ShaderDiagnostic(ShaderProblem.UNAVAILABLE_INPUT, detail = it.name) }

        val outputs = masterInputs.associate { pin ->
            pin.name to resolved.inputCode(master, pin, vertex = pin.name in vertexOutputs, sink = ArrayList())
        }
        return ShaderGraphCode(
            vertexLibraries = ShaderLibrary.ordered(vertex.libraries),
            fragmentLibraries = ShaderLibrary.ordered(fragment.libraries),
            properties = graph.properties,
            vertex = ShaderStageCode(vertex.statements),
            fragment = ShaderStageCode(fragment.statements),
            outputs = outputs,
            linkedOutputs = linked,
            inputs = used,
            diagnostics = diagnostics.distinct(),
            types = resolved.types(),
        )
    }

    /** Compiles every node of [graph] into one program the previews share. */
    fun compilePreview(graph: ShaderGraph): ShaderPreviewCode {
        val values = PreviewValues()
        val resolved = ResolvedGraph(graph, values)
        val nodes = resolved.order.filter { resolved.typeOf(it).master == null }
        val emitted = resolved.emit(nodes, vertex = false)

        val previewIndex = LinkedHashMap<String, Int>()
        val branches = ArrayList<String>()
        nodes.filter { resolved.typeOf(it).showsPreview(it) }.forEach { node ->
            val output = resolved.typeOf(node).outputs.first()
            val value = when (resolved.outputType(node, output)) {
                ShaderType.TEXTURE -> "texture(${resolved.variable(node, output)}, ${ShaderInput.UV.glsl})"
                else -> resolved.variable(node, output)
            }
            previewIndex[node.id] = previewIndex.size
            val shown = when (resolved.typeOf(node).previewStyle) {
                ShaderPreviewStyle.DISPLACEMENT -> "sg_show_shape()"
                ShaderPreviewStyle.SIGNED -> coerce(value, resolved.outputType(node, output), ShaderType.VEC3)
                    ?.let { "sg_show_signed($it)" } ?: "sg_show($value)"

                ShaderPreviewStyle.VALUE -> "sg_show($value)"
            }
            branches += "if (PreviewNode == ${previewIndex.getValue(node.id)}) fragColor = $shown;"
        }
        resolved.master?.takeIf { resolved.typeOf(it).showsPreview(it) }?.let { master ->
            val code = resolved.typeOf(master).inputs(master).associate { pin ->
                pin.name to resolved.inputCode(master, pin, vertex = false, sink = ArrayList())
            }
            previewIndex[master.id] = previewIndex.size
            val shown = when (graph.target) {
                ShaderTarget.SURFACE -> "sg_surface(${code[SurfaceOutputs.COLOR] ?: "vec3(1.0)"}, " +
                        "${code[SurfaceOutputs.ALPHA] ?: "1.0"}, ${code[SurfaceOutputs.EMISSION] ?: "vec3(0.0)"}, " +
                        "${code[SurfaceOutputs.ALPHA_CLIP] ?: "0.0"})"

                ShaderTarget.POST -> "sg_post(${code[PostOutputs.COLOR] ?: "vec3(1.0)"}, ${code[PostOutputs.ALPHA] ?: "1.0"})"
            }
            branches += "if (PreviewNode == ${previewIndex.getValue(master.id)}) fragColor = $shown;"
        }

        val moved = previewOffsets(graph, resolved, previewIndex)
        val movedNodes = moved.flatMap { it.nodes }.map { it.id }.toSet()
        val vertex = resolved.emit(resolved.order.filter { it.id in movedNodes }, vertex = true)
        return ShaderPreviewCode(
            libraries = ShaderLibrary.ordered(emitted.libraries),
            vertexLibraries = ShaderLibrary.ordered(vertex.libraries),
            vertex = ShaderStageCode(vertex.statements),
            vertexSelection = moved.joinToString("\n") { "if (PreviewNode == ${it.index}) sg_out_vertex_offset = ${it.offset};" },
            displaced = moved.map { it.node }.toSet(),
            properties = graph.properties,
            statements = ShaderStageCode(emitted.statements),
            statementNodes = emitted.owners,
            previewIndex = previewIndex,
            spatial = resolved.spatial(),
            values = values.slots,
            selection = "fragColor = vec4(0.0);\n" + branches.joinToString("\n"),
            diagnostics = resolved.diagnostics.distinct(),
        )
    }
}

private class PreviewOffset(val node: String, val index: Int, val offset: String, val nodes: List<ShaderGraphNode>)

private fun previewOffsets(graph: ShaderGraph, resolved: ResolvedGraph, previewIndex: Map<String, Int>): List<PreviewOffset> {
    val offsets = ArrayList<PreviewOffset>()
    resolved.master?.let { master ->
        val index = previewIndex[master.id] ?: return@let
        val link = graph.linkInto(master.id, SurfaceOutputs.VERTEX_OFFSET) ?: return@let
        val pin = resolved.typeOf(master).input(master, SurfaceOutputs.VERTEX_OFFSET) ?: return@let
        val nodes = resolved.reachableFrom(listOf(link.from))
        if (nodes.none(resolved::needsFragment)) {
            offsets += PreviewOffset(master.id, index, resolved.inputCode(master, pin, vertex = true, sink = ArrayList()), nodes)
        }
    }
    resolved.order.filter { resolved.typeOf(it).previewStyle == ShaderPreviewStyle.DISPLACEMENT }.forEach { node ->
        val index = previewIndex[node.id] ?: return@forEach
        val output = resolved.typeOf(node).outputs.first()
        val offset = coerce(resolved.variable(node, output), resolved.outputType(node, output), ShaderType.VEC3) ?: return@forEach
        val nodes = resolved.reachableFrom(listOf(node.id))
        if (nodes.none(resolved::needsFragment)) offsets += PreviewOffset(node.id, index, offset, nodes)
    }
    return offsets
}

/** The values of a preview program, each given a slot of the uniform array as the code reads it. */
private class PreviewValues {
    val slots = ArrayList<ShaderValueSlot>()
    private val taken = HashMap<Pair<String, String>, Int>()

    /** What reads the value of [pin] of [node], or null once the array is full and the value has to be a literal. */
    fun read(node: String, pin: String, type: ShaderType): String? {
        val index = taken.getOrPut(node to pin) {
            if (slots.size >= LIMIT) return null
            slots += ShaderValueSlot(node, pin, type.width)
            slots.size - 1
        }
        return when (type.width) {
            1 -> "PreviewValues[$index].x"
            2 -> "PreviewValues[$index].xy"
            3 -> "PreviewValues[$index].xyz"
            else -> "PreviewValues[$index]"
        }
    }

    private companion object {
        /** Well under the 256 vectors of uniforms a fragment stage is sure to have. */
        const val LIMIT = 192
    }
}

/** The code of some nodes in one stage, and what it needs from around it. */
private class EmittedStage(
    val statements: List<String>,
    val owners: List<String>,
    val libraries: Set<ShaderLibrary>,
    val inputs: Set<ShaderInput>,
)

/**
 * A graph with every node's kind looked up, the links checked, the nodes in dependency order and the
 * type of every pin worked out. With [values], typed values are read from the preview uniforms.
 */
private class ResolvedGraph(private val graph: ShaderGraph, private val values: PreviewValues?) {
    val diagnostics = ArrayList<ShaderDiagnostic>()
    private val kinds = LinkedHashMap<String, ShaderNodeType>()
    private val indices = HashMap<String, Int>()
    private val outputTypes = HashMap<Pair<String, String>, ShaderType>()
    private val inputTypes = HashMap<Pair<String, String>, ShaderType>()
    private val dynamics = HashMap<String, ShaderType>()

    val order: List<ShaderGraphNode>
    private val ordered: Set<String>
    val master: ShaderGraphNode?

    init {
        graph.nodes.forEachIndexed { index, node ->
            val kind = ShaderNodeTypes.of(node.type)
            if (kind == null) {
                diagnostics += ShaderDiagnostic(ShaderProblem.UNKNOWN_NODE, node.id, node.type)
            } else {
                kinds[node.id] = kind
                indices[node.id] = index
                kind.problem(node)?.let { diagnostics += it }
            }
        }
        graph.links.forEach { link ->
            if (graph.node(link.from) == null) diagnostics += ShaderDiagnostic(ShaderProblem.MISSING_NODE, link.to, link.from)
            if (graph.node(link.to) == null) diagnostics += ShaderDiagnostic(ShaderProblem.MISSING_NODE, link.from, link.to)
            val from = kinds[link.from]
            val to = graph.node(link.to)?.let { node -> kinds[node.id]?.let { node to it } }
            if (from != null && from.output(link.output) == null) {
                diagnostics += ShaderDiagnostic(ShaderProblem.UNKNOWN_PIN, link.from, link.output)
            }
            if (to != null && to.second.input(to.first, link.input) == null) {
                diagnostics += ShaderDiagnostic(ShaderProblem.UNKNOWN_PIN, link.to, link.input)
            }
        }

        val masters = graph.nodes.filter { kinds[it.id]?.master?.target == graph.target }
        master = masters.firstOrNull()
        if (masters.isEmpty()) diagnostics += ShaderDiagnostic(ShaderProblem.NO_OUTPUT_NODE)
        masters.drop(1).forEach { diagnostics += ShaderDiagnostic(ShaderProblem.SECOND_OUTPUT_NODE, it.id) }

        order = sortByDependencies()
        ordered = order.map { it.id }.toSet()
        order.forEach(::resolveTypes)
    }

    fun typeOf(node: ShaderGraphNode): ShaderNodeType = kinds.getValue(node.id)

    fun outputType(node: ShaderGraphNode, output: ShaderOutputSpec): ShaderType =
        outputTypes[node.id to output.name] ?: ShaderType.FLOAT

    /**
     * What the rest of the graph reads for an output: its variable, or for a texture the sampler it
     * names, since GLSL cannot hold a sampler in a local. A texture output reads no inputs but the
     * textures linked into it, which is how a reroute carries one along.
     */
    fun variable(node: ShaderGraphNode, output: ShaderOutputSpec, sink: MutableCollection<ShaderInput> = ArrayList()): String {
        if (outputType(node, output) == ShaderType.TEXTURE) {
            val kind = typeOf(node)
            val textures = kind.codeInputs(node).filter { inputTypes[node.id to it.name] == ShaderType.TEXTURE }
                .associate { pin -> pin.name to inputCode(node, pin, vertex = false, sink = sink) }
            val context = ShaderEmitContext(graph, node, ShaderType.FLOAT, ShaderType.TEXTURE, false, textures, emptyMap(), kind)
            return output.expression(context)
        }
        return "sg_${indices.getValue(node.id)}_${output.name.filter { it.isLetterOrDigit() }}"
    }

    fun types() = ShaderGraphTypes(outputTypes.toMap(), inputTypes.toMap())

    /** The link into [pin] of [node], when the node has that pin and it comes from an output of a usable node. */
    private fun source(node: ShaderGraphNode, pin: String): Pair<ShaderGraphNode, ShaderOutputSpec>? {
        val link = graph.linkInto(node.id, pin) ?: return null
        if (typeOf(node).input(node, pin) == null) return null
        val from = graph.node(link.from) ?: return null
        val output = kinds[from.id]?.output(link.output) ?: return null
        if (from.id !in ordered) return null
        return from to output
    }

    private fun sortByDependencies(): List<ShaderGraphNode> {
        val done = LinkedHashSet<String>()
        val path = ArrayList<String>()
        val cyclic = HashSet<String>()
        fun visit(node: ShaderGraphNode) {
            if (node.id in done) return
            val onPath = path.indexOf(node.id)
            if (onPath >= 0) {
                cyclic += path.subList(onPath, path.size)
                return
            }
            path += node.id
            graph.links.filter { it.to == node.id }.forEach { link ->
                graph.node(link.from)?.takeIf { it.id in kinds }?.let(::visit)
            }
            path.removeAt(path.lastIndex)
            done += node.id
        }
        graph.nodes.filter { it.id in kinds }.forEach(::visit)
        cyclic.forEach { diagnostics += ShaderDiagnostic(ShaderProblem.CYCLE, it) }
        return done.filter { it !in cyclic }.mapNotNull(graph::node)
    }

    private fun resolveTypes(node: ShaderGraphNode) {
        val kind = typeOf(node)
        val pins = kind.codeInputs(node)
        val incoming = pins.associateWith { pin ->
            source(node, pin.name)?.let { (from, output) -> outputType(from, output) }
                ?: pin.fallback?.type
                ?: pin.type.fixed
                ?: ShaderType.ofWidth(values(node, pin).size.coerceIn(1, 4))
        }
        val widest = pins.filter { it.type == ShaderPinType.DYNAMIC }
            .mapNotNull { pin -> incoming.getValue(pin).takeIf { it.isVector }?.width }
            .maxOrNull() ?: 1
        val dynamic = ShaderType.ofWidth(widest)
        dynamics[node.id] = dynamic

        pins.forEach { pin ->
            inputTypes[node.id to pin.name] = pin.type.fixed ?: if (pin.type.keepsLinkedType) incoming.getValue(pin) else dynamic
        }
        kind.outputs.forEach { output ->
            val declared = if (output.typeOf != null) output.typeOf.invoke(ShaderTypeContext(graph, node, typesOf(node))) else output.type
            val type = when {
                declared == null -> ShaderType.FLOAT.also {
                    diagnostics += ShaderDiagnostic(ShaderProblem.UNRESOLVED_TYPE, node.id, output.name)
                }

                else -> declared.fixed ?: dynamic
            }
            outputTypes[node.id to output.name] = type
        }
    }

    private fun typesOf(node: ShaderGraphNode): Map<String, ShaderType> =
        typeOf(node).codeInputs(node).associate { it.name to (inputTypes[node.id to it.name] ?: ShaderType.FLOAT) }

    private fun values(node: ShaderGraphNode, pin: ShaderPinSpec): List<Float> =
        node.values[pin.name]?.takeIf { it.isNotEmpty() } ?: pin.default.ifEmpty { listOf(0f) }

    /** Whether [node] can only run per fragment: it, or an engine input it falls back on, reads the scene or a derivative. */
    fun needsFragment(node: ShaderGraphNode): Boolean {
        val kind = typeOf(node)
        return kind.fragmentOnly || kind.reads.any { it.fragmentOnly } ||
                kind.inputs(node).any { pin -> pin.fallback?.fragmentOnly == true && source(node, pin.name) == null }
    }

    fun reachableFrom(roots: List<String>): List<ShaderGraphNode> {
        val reached = HashSet<String>()
        val stack = ArrayDeque(roots)
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!reached.add(id)) continue
            graph.links.filter { it.to == id }.forEach { stack += it.from }
        }
        return order.filter { it.id in reached && typeOf(it).master == null }
    }

    /**
     * The nodes that read the shape of what they are drawn on, the normal, the view or the position,
     * themselves or through what they are linked to; the output node always does.
     */
    fun spatial(): Set<String> {
        val shaped = setOf(ShaderInput.NORMAL, ShaderInput.VIEW_DIRECTION, ShaderInput.POSITION, ShaderInput.OBJECT_POSITION)
        val result = HashSet<String>()
        order.forEach { node ->
            val kind = typeOf(node)
            val own = kind.master != null || kind.reads.any { it in shaped } || kind.inputs(node).any { pin ->
                pin.fallback in shaped && source(node, pin.name) == null
            }
            val inherited = kind.inputs(node).any { pin -> source(node, pin.name)?.first?.id in result }
            if (own || inherited) result += node.id
        }
        return result
    }

    /**
     * The GLSL an input of [node] reads: the output linked to it, read as the pin's type, the engine
     * input it falls back on, or the value typed on the node. What it uses from the engine goes to [sink].
     */
    fun inputCode(node: ShaderGraphNode, pin: ShaderPinSpec, vertex: Boolean, sink: MutableCollection<ShaderInput>): String {
        val type = inputTypes[node.id to pin.name] ?: pin.type.fixed ?: ShaderType.FLOAT
        source(node, pin.name)?.let { (from, output) ->
            val fromType = outputType(from, output)
            coerce(variable(from, output, sink), fromType, type)?.let { return it }
            diagnostics += ShaderDiagnostic(ShaderProblem.TYPE_MISMATCH, node.id, "${pin.name}: $fromType -> $type")
        }
        pin.fallback?.let { input ->
            coerce(input.glsl, input.type, type)?.let { code ->
                sink += input
                if (vertex && input.fragmentOnly) {
                    diagnostics += ShaderDiagnostic(ShaderProblem.FRAGMENT_ONLY_IN_VERTEX, node.id, input.name)
                }
                return code
            }
        }
        if (type == ShaderType.TEXTURE) {
            sink += ShaderInput.MAIN_TEXTURE
            return ShaderInput.MAIN_TEXTURE.glsl
        }
        return values?.read(node.id, pin.name, type) ?: shaderLiteral(type, values(node, pin))
    }

    /** The statements of [nodes] for one stage, in their order. */
    fun emit(nodes: List<ShaderGraphNode>, vertex: Boolean): EmittedStage {
        val statements = ArrayList<String>()
        val owners = ArrayList<String>()
        val libraries = LinkedHashSet<ShaderLibrary>()
        val inputs = LinkedHashSet<ShaderInput>()
        nodes.forEach { node ->
            val kind = typeOf(node)
            if (vertex && (kind.fragmentOnly || kind.reads.any { it.fragmentOnly })) {
                diagnostics += ShaderDiagnostic(ShaderProblem.FRAGMENT_ONLY_IN_VERTEX, node.id, kind.id)
            }
            libraries += kind.libraries
            inputs += kind.reads
            val code = kind.codeInputs(node).associate { pin -> pin.name to inputCode(node, pin, vertex, inputs) }
            val written = LinkedHashMap<String, String>()
            kind.outputs.forEach { output ->
                val type = outputType(node, output)
                val name = variable(node, output)
                if (type != ShaderType.TEXTURE) {
                    val context = ShaderEmitContext(graph, node, dynamics.getValue(node.id), type, vertex, code, written, kind)
                    statements += "${type.glsl} $name = ${output.expression(context)};"
                    owners += node.id
                }
                written[output.name] = name
            }
        }
        return EmittedStage(statements, owners, libraries, inputs)
    }
}
