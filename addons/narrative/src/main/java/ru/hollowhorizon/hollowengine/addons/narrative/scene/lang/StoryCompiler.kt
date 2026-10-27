package ru.hollowhorizon.hollowengine.addons.narrative.scene.lang

import java.security.MessageDigest

data class StoryCompileResult(
    val program: StoryProgram?,
    val cst: StoryFileCst,
    val diagnostics: List<StoryDiagnostic>,
)

/** What the compiler learns about the stories a file imports, to check the calls into them. */
fun interface StoryImports {
    /** The `@define`s of the story [address], name to parameters, or null when there is no such story. */
    fun definitions(address: String): Map<String, List<String>>?
}

/**
 * Turns story text into a [StoryProgram]. Runs off the game thread: the result is cached by address
 * and source hash, so playback never parses anything.
 *
 * Structure comes from indentation. A line that opens a block (`@if`, `@while`, `@choice`, bare
 * `@async`) owns the more-indented lines below it; anything else may not be indented further than its
 * neighbors.
 */
class StoryCompiler(
    private val address: String,
    private val catalog: StoryFunctionCatalog = StoryFunctionCatalog.PERMISSIVE,
    private val imports: StoryImports? = null,
) {
    private val diagnostics = StoryDiagnostics()
    private val instructions = mutableListOf<StoryInstruction>()
    private val labels = LinkedHashMap<String, Int>()
    private val defines = LinkedHashMap<String, StoryDefinition>()

    /** The parameters of every `@define` of the file, known before any call to them is compiled. */
    private var declared: Map<String, List<String>> = emptyMap()

    /** The stories the file imports, and the `@define`s each of them has, when [imports] can tell. */
    private val imported = LinkedHashMap<String, Map<String, List<String>>?>()

    /** The `@define` whose body is being compiled. */
    private var defining: String? = null

    /** Section the following instructions belong to, and where it started, the anchor basis. */
    private var currentLabel: String? = null
    private var sectionStart = 0

    /** The `@temporary` and `@timeout` blocks the instructions being compiled are in, innermost last. */
    private val temporaries = ArrayDeque<StoryTemporary>()
    private val timeouts = ArrayDeque<Int>()
    private var asyncDepth = 0
    private val checkpoints = HashSet<String>()

    companion object {
        /** Universal named parameters every function accepts on top of its own signature. */
        private val RESERVED_PARAMS = setOf("tag")

        /** The parameter of `@stage` naming the variable its result goes to. */
        private const val RESULT = "result"

        fun hash(source: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun compile(
            address: String,
            source: String,
            catalog: StoryFunctionCatalog = StoryFunctionCatalog.PERMISSIVE,
            imports: StoryImports? = null,
        ): StoryCompileResult = StoryCompiler(address, catalog, imports).compile(source)

        /**
         * The address [written] in the story [from] means: as it is with a namespace, `project:stories/a.story`, and
         * next to [from] without one, so `common/greetings.story` in `project:stories/a.story` is
         * `project:stories/common/greetings.story`.
         */
        fun resolveAddress(from: String, written: String): String {
            if (':' in written) return written
            val namespace = from.substringBefore(':', "")
            val directory = from.substringAfter(':').substringBeforeLast('/', "")
            val path = if (directory.isEmpty()) written else "$directory/$written"
            return if (namespace.isEmpty()) path else "$namespace:$path"
        }

        /** The `@define`s of [source], name to parameters, read without compiling it. */
        fun definitionsOf(source: String): Map<String, List<String>> =
            StoryParser.parse(source).cst.lines.mapNotNull { line ->
                (line.kind as? StoryLineKind.Define)?.takeIf { line.indent.isEmpty() }?.let { it.name to it.params }
            }.toMap()
    }

    fun compile(source: String): StoryCompileResult {
        val parsed = StoryParser.parse(source)
        parsed.diagnostics.all.forEach { record(it) }

        val roots = buildTree(parsed.cst.lines)
        declared = roots.mapNotNull { (it.line.kind as? StoryLineKind.Define)?.let { define -> define.name to define.params } }.toMap()
        roots.forEach { node ->
            val import = node.line.kind as? StoryLineKind.Import ?: return@forEach
            val target = resolveAddress(address, import.address)
            val known = imports?.definitions(target)
            if (imports != null && known == null) error("Story '$target' not found", node.line)
            imported[target] = known
        }
        compileNodes(roots, topLevel = true)

        val program = if (diagnostics.hasErrors) null else StoryProgram(
            address = address,
            sourceHash = hash(source),
            instructions = instructions.toList(),
            labels = labels.toMap(),
            defines = defines.toMap(),
            imports = imported.keys.toList(),
        )
        return StoryCompileResult(program, parsed.cst, diagnostics.all)
    }

    private fun record(diagnostic: StoryDiagnostic) {
        when (diagnostic.severity) {
            StorySeverity.ERROR -> diagnostics.error(diagnostic.message, diagnostic.span)
            StorySeverity.WARNING -> diagnostics.warning(diagnostic.message, diagnostic.span)
        }
    }

    private class Node(val line: StoryLine, val children: MutableList<Node> = mutableListOf())

    private fun indentWidth(indent: String): Int = indent.sumOf { if (it == '\t') 4 else 1 }

    private fun buildTree(lines: List<StoryLine>): List<Node> {
        val roots = mutableListOf<Node>()
        val stack = ArrayDeque<Pair<Node, Int>>()

        for (line in lines) {
            if (line.kind is StoryLineKind.Blank || line.kind is StoryLineKind.CommentOnly) continue
            if (line.kind is StoryLineKind.Broken) continue

            val width = indentWidth(line.indent)
            while (stack.isNotEmpty() && width <= stack.last().second) stack.removeLast()

            val node = Node(line)
            val parent = stack.lastOrNull()?.first
            if (parent == null) {
                if (width > 0 && roots.isNotEmpty()) {
                    error("Unexpected indentation: the line above does not open a block", line)
                }
                roots += node
            } else {
                parent.children += node
            }
            if (line.opensBlock) stack.addLast(node to width)
        }
        return roots
    }

    private fun compileNodes(nodes: List<Node>, topLevel: Boolean = false) {
        var i = 0
        while (i < nodes.size) {
            val node = nodes[i]
            if (!topLevel && node.line.kind is StoryLineKind.Label) {
                error("Labels must be at the top level of the file, not inside a block", node.line)
            }
            if (!topLevel && (node.line.kind is StoryLineKind.Define || node.line.kind is StoryLineKind.Import)) {
                error("'@define' and '@import' belong at the top level of the file", node.line)
            }
            i = when (node.line.kind) {
                is StoryLineKind.If -> compileIfChain(nodes, i)
                is StoryLineKind.Choice -> compileChoiceGroup(nodes, i)
                is StoryLineKind.ElseIf, is StoryLineKind.Else -> {
                    error(
                        "'@${if (node.line.kind is StoryLineKind.Else) "else" else "else-if"}' without a matching '@if'",
                        node.line
                    )
                    i + 1
                }

                else -> {
                    compileSimple(node)
                    i + 1
                }
            }
        }
    }

    private fun compileSimple(node: Node) {
        val line = node.line
        when (val kind = line.kind) {
            is StoryLineKind.Label -> {
                if (labels.containsKey(kind.name)) {
                    error("Duplicate label '${kind.name}'", line)
                } else {
                    labels[kind.name] = instructions.size
                }
                currentLabel = kind.name
                sectionStart = instructions.size
                requireNoChildren(node)
            }

            is StoryLineKind.Dialogue -> {
                validateTemplate(kind.text)
                emit(StoryInstruction.Say(kind.speaker, kind.text, anchor(line), kind.speakerExpr))
                requireNoChildren(node)
            }

            is StoryLineKind.Set -> {
                emit(StoryInstruction.Set(kind.variable, kind.value, anchor(line)))
                requireNoChildren(node)
            }

            is StoryLineKind.While -> compileWhile(node, kind)

            is StoryLineKind.Jump -> {
                when {
                    defining != null -> error("'@jump' cannot leave a '@define'; '@return' from it instead", line)
                    kind.target.address != null -> error(
                        "'@jump' stays within its file; '@call' another story to play it and come back", line,
                    )
                }
                emit(StoryInstruction.Jump(target(kind.target), anchor(line)))
                requireNoChildren(node)
            }

            is StoryLineKind.Call -> {
                emit(StoryInstruction.Call(target(kind.target), anchor(line)))
                requireNoChildren(node)
            }

            is StoryLineKind.Return -> {
                if (kind.value != null && defining == null) error("Only a '@define' returns a value", line)
                emit(StoryInstruction.Return(anchor(line), kind.value))
                requireNoChildren(node)
            }

            is StoryLineKind.Define -> compileDefine(node, kind)

            is StoryLineKind.CallDefine -> {
                val params = declared[kind.name] ?: importedParams(kind.name, line)
                if (params != null && params.size != kind.args.size) {
                    error("'${kind.name}' takes ${params.size} arguments: ${params.joinToString()}", line)
                }
                emit(StoryInstruction.CallDefine(kind.name, kind.args.map { it.expr }, kind.result, anchor(line)))
                requireNoChildren(node)
            }

            is StoryLineKind.Import -> requireNoChildren(node)

            is StoryLineKind.Stage -> {
                val result = kind.args.firstOrNull { it.name == RESULT }
                val name = result?.let { arg ->
                    literalString(arg) ?: run {
                        error("'result' must be a variable name", line)
                        null
                    }
                }
                emit(StoryInstruction.Stage(kind.script, kind.stage, kind.args - setOfNotNull(result), name, anchor(line)))
                requireNoChildren(node)
            }

            is StoryLineKind.Command -> {
                validateTemplate(kind.text)
                emit(StoryInstruction.Command(kind.text, anchor(line)))
                requireNoChildren(node)
            }

            is StoryLineKind.FuncCall -> {
                emitInvoke(resolveCall(kind, line), line)
                requireNoChildren(node)
            }

            is StoryLineKind.Checkpoint -> {
                if (asyncDepth > 0) error("'@checkpoint' belongs to the main flow, not to an '@async' track", line)
                if (!checkpoints.add(kind.name)) error("Duplicate checkpoint '${kind.name}'", line)
                emit(StoryInstruction.Checkpoint(kind.name, anchor(line)))
                requireNoChildren(node)
            }

            is StoryLineKind.Rewind -> {
                if (asyncDepth > 0) error("'@rewind' belongs to the main flow, not to an '@async' track", line)
                emit(StoryInstruction.Rewind(kind.name, anchor(line)))
                requireNoChildren(node)
            }

            is StoryLineKind.Temporary -> {
                if (node.children.isEmpty()) error("'@temporary' expects an indented block", line)
                temporaries.addLast(StoryTemporary(kind.until))
                compileNodes(node.children)
                temporaries.removeLast()
            }

            is StoryLineKind.Timeout -> compileTimeout(node, kind)

            is StoryLineKind.Async -> compileAsync(node, kind)

            is StoryLineKind.Await -> {
                emit(StoryInstruction.Await(kind.trackNames, anchor(line)))
                requireNoChildren(node)
            }

            is StoryLineKind.Cancel -> {
                emit(StoryInstruction.Cancel(kind.trackName, anchor(line)))
                requireNoChildren(node)
            }

            is StoryLineKind.Sync -> {
                emit(StoryInstruction.Sync(anchor(line)))
                requireNoChildren(node)
            }

            is StoryLineKind.Blank, is StoryLineKind.CommentOnly, is StoryLineKind.Broken -> Unit
            is StoryLineKind.If, is StoryLineKind.ElseIf, is StoryLineKind.Else,
            is StoryLineKind.Choice,
                -> error("Internal: ${kind::class.simpleName} handled elsewhere", line)
        }
    }

    /** `@if` + its `@else-if`/`@else` siblings; returns the index just past the chain. */
    private fun compileIfChain(nodes: List<Node>, start: Int): Int {
        var i = start
        val endJumps = mutableListOf<Int>()
        var sawElse = false

        while (i < nodes.size) {
            val node = nodes[i]
            val condition = when (val kind = node.line.kind) {
                is StoryLineKind.If -> if (i == start) kind.condition else break
                is StoryLineKind.ElseIf -> if (i == start) break else kind.condition
                is StoryLineKind.Else -> if (i == start) break else null
                else -> break
            }
            if (sawElse) {
                error("'@else' must be the last branch of the chain", node.line)
                break
            }

            if (condition != null) {
                val branchJump = emitPlaceholder(node.line)
                compileNodes(node.children)
                val skip = emitPlaceholder(node.line)
                endJumps += skip
                patch(branchJump, StoryInstruction.GotoIfFalse(condition, instructions.size, anchor(node.line)))
                patchGoto(skip)
            } else {
                sawElse = true
                compileNodes(node.children)
            }
            i++
        }

        val end = instructions.size
        endJumps.forEach { patch(it, StoryInstruction.Goto(end, instructions[it].anchor)) }
        return i
    }

    private fun compileWhile(node: Node, kind: StoryLineKind.While) {
        val conditionPc = instructions.size
        val exitJump = emitPlaceholder(node.line)
        compileNodes(node.children)
        emit(StoryInstruction.Goto(conditionPc, anchor(node.line)))
        patch(exitJump, StoryInstruction.GotoIfFalse(kind.condition, instructions.size, anchor(node.line)))
    }

    /** Consecutive `@choice` siblings form one menu shown at once. */
    private fun compileChoiceGroup(nodes: List<Node>, start: Int): Int {
        var i = start
        val group = mutableListOf<Node>()
        while (i < nodes.size && nodes[i].line.kind is StoryLineKind.Choice) {
            group += nodes[i]
            i++
        }

        val menuPc = emitPlaceholder(group.first().line)
        val options = mutableListOf<MenuOption>()
        val endJumps = mutableListOf<Int>()

        for (node in group) {
            val kind = node.line.kind as StoryLineKind.Choice
            validateTemplate(kind.text)
            val bodyStart = instructions.size
            compileNodes(node.children)
            endJumps += emitPlaceholder(node.line)

            var id: String? = null
            var condition: StoryExpression? = null
            val rest = mutableListOf<StoryArg>()
            for (arg in kind.args) {
                when (arg.name) {
                    "id" -> id = literalString(arg) ?: run {
                        error("'id' of a choice must be a plain word", node.line)
                        null
                    }

                    "if" -> condition = arg.expr
                    null -> error("'@choice' takes only named parameters after its text", node.line)
                    else -> rest += arg
                }
            }
            options += MenuOption(id, kind.text, condition, rest, bodyStart, node.line.index)
        }

        val end = instructions.size
        endJumps.forEach { patch(it, StoryInstruction.Goto(end, instructions[it].anchor)) }
        patch(menuPc, StoryInstruction.Menu(options, end, instructions[menuPc].anchor))

        val duplicateIds = options.mapNotNull { it.id }.groupBy { it }.filterValues { it.size > 1 }.keys
        duplicateIds.forEach { duplicate ->
            error("Duplicate choice id '$duplicate' in one menu", group.first().line)
        }
        return i
    }

    /**
     * `@timeout` holds commands and the control flow between them: a line or a menu would wait for the players, who
     * are not to be hurried, and a track would outlive the block.
     */
    private fun compileTimeout(node: Node, kind: StoryLineKind.Timeout) {
        if (node.children.isEmpty()) error("'@timeout' expects an indented block", node.line)
        val startPc = emitPlaceholder(node.line)
        timeouts.addLast(startPc)
        compileNodes(node.children)
        timeouts.removeLast()
        patch(startPc, StoryInstruction.TimeoutStart(kind.duration, instructions.size, instructions[startPc].anchor))
        for (pc in startPc + 1 until instructions.size) {
            val what = when (instructions[pc]) {
                is StoryInstruction.Say -> "Dialogue lines"
                is StoryInstruction.Menu -> "Choices"
                is StoryInstruction.AsyncStart -> "'@async' tracks"
                else -> continue
            }
            errorAt("$what are not allowed inside '@timeout'", instructions[pc].anchor)
        }
    }

    /**
     * A `@define` body is a section of its own, so a save inside it survives edits around it, and the flow reading
     * the file top-down jumps over it.
     */
    private fun compileDefine(node: Node, kind: StoryLineKind.Define) {
        if (defines.containsKey(kind.name)) error("Duplicate '@define ${kind.name}'", node.line)
        if (node.children.isEmpty()) error("'@define' expects an indented block", node.line)
        val skip = emitPlaceholder(node.line)
        val outerLabel = currentLabel
        val outerStart = sectionStart
        val start = instructions.size
        currentLabel = defineSection(kind.name)
        sectionStart = start
        labels[defineSection(kind.name)] = start
        defines[kind.name] = StoryDefinition(kind.name, kind.params, start)
        defining = kind.name
        compileNodes(node.children)
        emit(StoryInstruction.Return(anchor(node.line)))
        defining = null
        currentLabel = outerLabel
        sectionStart = outerStart
        patch(skip, StoryInstruction.Goto(instructions.size, instructions[skip].anchor))
    }

    /** The parameters of the imported `@define` [name], or null when the imports cannot be checked. */
    private fun importedParams(name: String, line: StoryLine): List<String>? {
        if (imported.values.any { it == null }) return null
        val found = imported.filterValues { it!!.containsKey(name) }
        when {
            found.isEmpty() -> error("No '@define $name' in this story or the ones it imports", line)
            found.size > 1 -> error("'$name' is defined by several imports: ${found.keys.joinToString()}", line)
        }
        return found.values.firstOrNull()?.get(name)
    }

    private fun emitInvoke(call: StoryCall, line: StoryLine) =
        emit(StoryInstruction.Invoke(call, anchor(line), temporaries.lastOrNull(), timeouts.toList()))

    private fun compileAsync(node: Node, kind: StoryLineKind.Async) {
        if (defining != null) error("'@async' cannot start inside a '@define'", node.line)
        val startPc = emitPlaceholder(node.line)
        val bodyStart = instructions.size
        asyncDepth++
        if (kind.inline != null) {
            if (node.children.isNotEmpty()) {
                error("'@async' with an inline command cannot also open a block", node.line)
            }
            emitInvoke(resolveCall(kind.inline, node.line), node.line)
        } else {
            if (node.children.isEmpty()) {
                error("'@async' expects an indented block or a command on the same line", node.line)
            }
            compileNodes(node.children)
        }
        asyncDepth--
        val bodyEnd = instructions.size
        patch(startPc, StoryInstruction.AsyncStart(kind.trackName, bodyStart, bodyEnd, instructions[startPc].anchor))

        val syncPc = (bodyStart until bodyEnd).firstOrNull { instructions[it] is StoryInstruction.Sync } ?: bodyEnd
        for (pc in bodyStart until syncPc) {
            when (instructions[pc]) {
                is StoryInstruction.Say -> errorAt(
                    "Dialogue lines are not allowed inside '@async' before '@sync'", instructions[pc].anchor
                )

                is StoryInstruction.Menu -> errorAt(
                    "Choices are not allowed inside '@async' before '@sync'", instructions[pc].anchor
                )

                else -> {}
            }
        }
    }

    private fun resolveCall(kind: StoryLineKind.FuncCall, line: StoryLine): StoryCall {
        var tag: String? = null
        val metadata = LinkedHashMap<String, StoryExpression>()
        val positional = mutableListOf<StoryArg>()
        val named = LinkedHashMap<String, StoryArg>()

        for (arg in kind.args) {
            when (val name = arg.name) {
                null -> {
                    if (named.isNotEmpty()) {
                        error("Positional arguments must come before named ones", line)
                    }
                    positional += arg
                }

                "tag" -> tag = literalString(arg) ?: run {
                    error("'tag' must be a plain word or quoted string", line)
                    null
                }

                else -> {
                    if (named.put(name, arg) != null) error("Duplicate argument '$name'", line)
                }
            }
        }

        val overloads = catalog.overloads(kind.function)
        if (overloads == null) {
            warning("Unknown command '@${kind.function}', it must be registered before the story runs", line)
            return StoryCall(kind.function, positional + named.values, tag, metadata, kind.functionSpan)
        }

        val declaredBy = overloads.map { it.name }.distinct()
        if (declaredBy.size > 1) {
            error(
                "Several namespaces have '@${kind.function}', write ${declaredBy.joinToString(" or ") { "@$it" }}",
                line
            )
        } else if (overloads.isNotEmpty()) {
            val viable = overloads.filter { matches(it, positional, named) }
            if (viable.isEmpty()) {
                error(
                    "No overload of '@${kind.function}' accepts these arguments " + "(candidates: ${
                        overloads.joinToString(
                            ", "
                        ) { describe(it) }
                    })",
                    line,
                )
            } else {
                val declared = viable.flatMap { sig -> sig.params.map { it.name } }.toSet()
                named.entries.removeIf { (name, arg) ->
                    if (name in declared || name in RESERVED_PARAMS) false
                    else {
                        metadata[name] = arg.expr
                        true
                    }
                }
            }
        }
        return StoryCall(kind.function, positional + named.values, tag, metadata, kind.functionSpan)
    }

    private fun matches(
        signature: StoryFunctionSignature,
        positional: List<StoryArg>,
        named: Map<String, StoryArg>,
    ): Boolean {
        if (positional.size > signature.params.size) return false
        val bound = HashSet<String>()
        positional.forEachIndexed { index, arg ->
            val param = signature.params[index]
            if (!literalFits(param, arg)) return false
            bound += param.name
        }
        for ((name, arg) in named) {
            val param = signature.params.firstOrNull { it.name == name } ?: continue
            if (name in bound) return false
            if (!literalFits(param, arg)) return false
            bound += name
        }
        return signature.params.none { !it.optional && it.name !in bound }
    }

    /** Only literal arguments can be type-checked ahead of time; anything dynamic passes. */
    private fun literalFits(param: StoryParam, arg: StoryArg): Boolean {
        val literal = arg.expr.constant ?: return true
        return param.type.accepts(literal)
    }

    private fun describe(signature: StoryFunctionSignature): String =
        signature.params.joinToString(", ", "${signature.name}(", ")") {
            "${it.name}: ${it.type.name.lowercase()}${if (it.optional) "?" else ""}"
        }

    private fun literalString(arg: StoryArg): String? =
        (arg.expr.constant as? ru.hollowhorizon.hollowengine.addons.narrative.scene.StoryString)?.value

    private fun validateTemplate(template: TextTemplate) {
        for (part in template.parts) {
            if (part is TextPart.InlineCall && catalog.overloads(part.call.function) == null) {
                diagnostics.warning(
                    "Unknown inline command '[${part.call.function}]'. It must be registered before the story runs.",
                    part.span,
                )
            }
        }
    }

    private fun anchor(line: StoryLine) = StoryAnchor(currentLabel, instructions.size - sectionStart, line.index)

    private fun emit(instruction: StoryInstruction) {
        instructions += instruction
    }

    /** Reserves a slot whose target is only known once the block below it is compiled. */
    private fun emitPlaceholder(line: StoryLine): Int {
        val index = instructions.size
        instructions += StoryInstruction.Goto(index + 1, anchor(line))
        return index
    }

    private fun patch(index: Int, instruction: StoryInstruction) {
        instructions[index] = instruction
    }

    private fun patchGoto(index: Int) {
        patch(index, StoryInstruction.Goto(index + 1, instructions[index].anchor))
    }

    private fun target(ref: StoryRef) = StoryTarget(ref.address, ref.label, ref.span)

    private fun requireNoChildren(node: Node) {
        node.children.firstOrNull()?.let {
            error("This line does not open a block, so the lines below it must not be indented", it.line)
        }
    }

    private fun error(message: String, line: StoryLine) {
        diagnostics.error(message, lineSpan(line))
    }

    private fun warning(message: String, line: StoryLine) {
        diagnostics.warning(message, lineSpan(line))
    }

    private fun lineSpan(line: StoryLine): StorySpan {
        val start = line.offset + line.indent.length
        val end = line.offset + (line.commentStart ?: line.raw.length)
        return StorySpan(start, maxOf(start, end), line.index)
    }

    private fun errorAt(message: String, anchor: StoryAnchor) {
        diagnostics.error(message, StorySpan(0, 0, anchor.line))
    }
}
