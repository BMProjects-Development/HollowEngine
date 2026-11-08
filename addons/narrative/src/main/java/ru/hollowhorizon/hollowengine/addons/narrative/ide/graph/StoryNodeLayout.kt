package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import kotlin.math.ceil
import kotlin.reflect.full.starProjectedType
import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.addInput
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.removeInput
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.removeOption
import ru.hollowhorizon.hollowengine.addons.narrative.ide.*
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCommand
import ru.hollowhorizon.hollowengine.addons.narrative.story.TypeOperators
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphNodeMetrics
import ru.hollowhorizon.hollowengine.client.ui.widgets.ModelClipPreview
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContributor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextCompletion
import ru.hollowhorizon.hollowengine.common.utils.Spelling

/**
 * Lays the nodes of [graph] out with the fields that edit them in [state].
 *
 * @param sources the exact type the output of a node gives, by node and pin, which the generic pins of a block take.
 * @param consumers the exact type the input of a node takes, by node and pin, which the generic pins of a block take
 * when nothing linked into them says.
 */
internal class StoryNodeLayout(
    internal val graph: StoryGraph,
    internal val state: StoryEditorState,
    private val sources: (String, String) -> String? = { _, _ -> null },
    private val consumers: (String, String) -> String? = { _, _ -> null },
) {
    private val linked = graph.index.values.keys
    private val types = StoryInsight.analysis(state, state.root() ?: graph).types

    /**
     * The box of [node], with its corner at [place].
     *
     * @param detailed whether to add the fields too rare to take up room on a node, for the inspector.
     */
    fun of(node: StoryNode, place: StoryPoint, detailed: Boolean = false): StoryNodeBox {
        if (node is StoryNode.Reroute) return reroute(node, place)
        val rows = Rows(detailed)
        val width = rows.build(node)
        val collapsed = node.id in graph.collapsed
        return StoryNodeBox(
            node, titleOf(node), iconOf(node), tagOf(node), colorOf(node), place.x, place.y, width,
            if (collapsed) GraphNodeMetrics.COLLAPSED else rows.bottom(), rows.result, collapsed, rows.bands,
        )
    }

    private fun reroute(node: StoryNode.Reroute, place: StoryPoint): StoryNodeBox {
        val size = GraphNodeMetrics.REROUTE
        val type = if (node.flow) StoryPinType.FLOW else StoryPinType.ANY
        val row = StoryNodeRow(
            size / 2f - StoryNodeBox.FLOW_ROW / 2f, size,
            input = StoryPinSpec(node.input, type), output = StoryPinSpec(node.output, type),
        )
        return StoryNodeBox(
            node,
            storyLang("reroute"),
            null,
            "",
            null,
            place.x,
            place.y,
            size,
            size,
            listOf(row),
            false
        )
    }

    private fun titleOf(node: StoryNode): String = when (node) {
        is StoryNode.Call -> StoryCatalog.signatureOf(node)?.let(StoryCatalog::title)
            ?: node.function.substringAfterLast('.')

        is StoryNode.Function -> node.name
        is StoryNode.Invoke -> node.function

        is StoryNode.Pure -> StoryCatalog.operatorTitle(node.operator)
        else -> StoryNodeKind.of(node).title
    }

    /** The icon of [node]: that of its command or its category, or else that of its kind. */
    private fun iconOf(node: StoryNode): String = when (node) {
        is StoryNode.Call -> StoryCatalog.signatureOf(node)?.let(StoryCatalog::icon)
        is StoryNode.Pure -> StoryOperators.find(node.operator)?.let(StoryCatalog::operatorIcon)
        else -> null
    } ?: StoryNodeKind.of(node).icon

    private fun tagOf(node: StoryNode): String = "st-${StoryNodeKind.of(node).tag}"

    /** The color of [node]: that of its category, or else that of its kind. */
    private fun colorOf(node: StoryNode): UiColor = when (node) {
        is StoryNode.Call -> StoryCatalog.signatureOf(node)?.let(StoryCatalog::color)
        is StoryNode.Pure -> StoryOperators.find(node.operator)?.let(StoryCatalog::operatorColor)
        else -> null
    } ?: StoryNodeKind.of(node).color

    private fun edit(node: String, field: String, change: (StoryNode) -> StoryNode) =
        state.editNode(node, field, change)

    /** The rows of one node, each under the one before. */
    internal inner class Rows(internal val detailed: Boolean) {
        /** The layout the rows are of. */
        val layout: StoryNodeLayout get() = this@StoryNodeLayout

        val result = ArrayList<StoryNodeRow>()
        val bands = ArrayList<StoryRowBand>()
        private var y = GraphNodeMetrics.HEADER + PADDING

        /** Where the heading of the folded section the rows are added in sits, while they are added in one. */
        private var foldedAt: Float? = null

        /** How many sections the rows are added inside of. */
        private var depth = 0

        fun bottom() = y + PADDING

        /** Whether a value has more beside its field than its name, the parts of a vector or the checkbox of a flag. */
        private var crowded = false

        /** The width the rows added so far take, the same with a value linked as with its field in place. */
        fun fitted(): Float = if (crowded) WIDE else GraphNodeMetrics.WIDTH

        internal fun row(
            label: String? = null,
            input: StoryPinSpec? = null,
            output: StoryPinSpec? = null,
            field: StoryRowField? = null,
            onRemove: (() -> Unit)? = null,
            onAdd: (() -> Unit)? = null,
            hint: String? = null,
            optional: Boolean = false,
            addLabel: String? = null,
            nameField: StoryRowField? = null,
            section: StoryRowSection? = null,
        ) {
            foldedAt?.let { heading ->
                result += StoryNodeRow(heading, 0f, label, input, output, field, section = section, hidden = true)
                return
            }
            val height = if (field != null && field.lines > 1) field.lines * LINE + 12f else StoryNodeBox.FLOW_ROW
            result += StoryNodeRow(
                y, height, label, input, output, field, onRemove, onAdd, hint, optional, addLabel, nameField, section,
                depth = depth,
            )
            y += height
        }

        /**
         * A heading that folds the rows [content] adds, closed until the author opens it, with the pin of the whole
         * value they are parts of on the side it is on.
         *
         * @param key what the editor remembers the section being open by.
         */
        internal fun section(
            key: String,
            label: String,
            input: StoryPinSpec? = null,
            output: StoryPinSpec? = null,
            content: () -> Unit,
        ) {
            val open = key in state.openSections
            val outer = foldedAt
            if (outer == null) y += SECTION_GAP
            val top = y
            row(label, input, output, section = StoryRowSection(open) { state.toggleSection(key) })
            if (!open && outer == null) foldedAt = result.last().top
            depth++
            content()
            depth--
            foldedAt = outer
            if (outer == null) bands += StoryRowBand(top, y, depth)
        }

        /**
         * Rows of [items], each one whose [path] is under that of an earlier one folded into a section that one
         * heads: [row] adds the row of an item, and [heading] that of an item at the head of a section, whose rows
         * it adds by calling what it is given.
         */
        internal fun <T> folded(
            items: List<T>,
            path: (T) -> String,
            row: (T) -> Unit,
            heading: (T, content: () -> Unit) -> Unit,
        ) {
            var index = 0
            while (index < items.size) {
                val item = items[index]
                val prefix = path(item) + "."
                val inner = items.subList(index + 1, items.size).takeWhile { path(it).startsWith(prefix) }
                if (inner.isEmpty()) row(item) else heading(item) { folded(inner, path, row, heading) }
                index += 1 + inner.size
            }
        }

        /** What the editor remembers the section of [path] of [node] being open by. */
        internal fun sectionKey(node: String, path: String) = "pins:$node:$path"

        internal fun flow(hasInput: Boolean = true, hasOutput: Boolean = true) = row(
            input = FLOW_IN.takeIf { hasInput },
            output = FLOW_NEXT.takeIf { hasOutput },
        )

        private fun exit(pin: String, label: String) = row(label = label, output = StoryPinSpec(pin, StoryPinType.FLOW))

        /** A value pin with what it holds when nothing is linked to it. */
        internal fun value(
            node: String,
            name: String,
            label: String,
            type: StoryPinType,
            field: StoryRowField?,
            hint: String? = null,
            optional: Boolean = false,
            kotlin: String? = null,
            onRemove: (() -> Unit)? = null,
        ) {
            val free = (node to name) !in linked
            val form = field?.form
            if (form is StoryFieldForm.Vector || form == StoryFieldForm.Flag) crowded = true
            row(
                label,
                StoryPinSpec(name, type, kotlin),
                field = field.takeIf { free },
                hint = hint,
                optional = optional,
                onRemove = onRemove
            )
        }

        fun build(node: StoryNode): Float = when (node) {
            is StoryNode.Start -> {
                row(label = storyLang("start.hint"), output = FLOW_NEXT)
                GraphNodeMetrics.WIDTH
            }

            is StoryNode.Function -> {
                signature(node)
                GraphNodeMetrics.WIDTH
            }

            is StoryNode.Parameters -> {
                parameters(node)
                GraphNodeMetrics.WIDTH
            }

            is StoryNode.Return -> {
                giveBack(node)
                WIDE
            }

            is StoryNode.Invoke -> {
                invoke(node)
                WIDE
            }

            is StoryNode.Signal -> {
                signal(node)
                GraphNodeMetrics.WIDTH
            }

            is StoryNode.Say -> {
                flow()
                value(
                    node.id, StoryPins.SPEAKER, storyLang("phrase.speaker"), StoryPinType.TEXT,
                    StoryRowField("speaker", node.speaker, storyLang("narrator")) { typed ->
                        edit(node.id, "speaker") { (it as StoryNode.Say).copy(speaker = typed) }
                    },
                    storyLang("phrase.speaker.help"),
                )
                value(
                    node.id, StoryPins.TEXT, storyLang("phrase.text"), StoryPinType.TEXT,
                    StoryRowField(
                        "text",
                        node.text,
                        storyLang("phrase.text"),
                        linesOf(node.text, SAY_COLUMNS)
                    ) { typed ->
                        edit(node.id, "text") { (it as StoryNode.Say).copy(text = typed) }
                    },
                    storyLang("phrase.text.help"),
                )
                WIDE
            }

            is StoryNode.Call -> {
                call(node)
                WIDE
            }

            is StoryNode.Choose -> {
                flow(hasOutput = false)
                node.options.forEachIndexed { index, option -> option(node, index, option) }
                row(onAdd = {
                    state.editNode(node.id, "add-option") { each ->
                        each as StoryNode.Choose
                        each.copy(options = each.options + StoryOption(storyLang("new.option")))
                    }
                })
                WIDE
            }

            is StoryNode.Branch -> {
                flow(hasOutput = false)
                value(
                    node.id, StoryPins.CONDITION, storyLang("branch.placeholder"), StoryPinType.BOOL,
                    StoryRowField(
                        "condition",
                        node.condition,
                        "x > 0",
                        code = true,
                        problem = codeProblem(node.condition)
                    ) { typed ->
                        edit(node.id, "condition") { (it as StoryNode.Branch).copy(condition = typed) }
                    },
                )
                exit(StoryPins.THEN, storyLang("branch.then"))
                exit(StoryPins.ELSE, storyLang("branch.else"))
                GraphNodeMetrics.WIDTH
            }

            is StoryNode.Assign -> {
                val written = graph.variables.firstOrNull { it.name == node.variable }?.type
                val type = StoryPinType.ofName(written)
                val kotlin = written
                flow()
                row(
                    label = storyLang("set.variable"),
                    field = StoryRowField(
                        "variable",
                        node.variable,
                        choices = graph.variables.map { it.name }) { typed ->
                        edit(node.id, "variable") { (it as StoryNode.Assign).copy(variable = typed.trim()) }
                    },
                )
                val valueType = StoryFieldForm.typeNamed(written)
                val form = StoryFieldForm.of(valueType)
                value(
                    node.id, StoryPins.VALUE, storyLang("set.value"), type,
                    StoryRowField(
                        "value", form.shown(node.value, valueType), "0", code = form != StoryFieldForm.Literal,
                        form = form,
                        choices = form.choices,
                        problem = codeProblem(node.value),
                    ) { typed ->
                        edit(node.id, "value") { (it as StoryNode.Assign).copy(value = form.written(typed, valueType)) }
                    },
                    kotlin = kotlin,
                )
                row(
                    storyLang("set.result"),
                    output = StoryPinSpec(StoryPins.VALUE, type, kotlin),
                    hint = storyLang("set.result.help")
                )
                fitted()
            }

            is StoryNode.Loop -> {
                flow()
                value(
                    node.id, StoryPins.ITEMS, storyLang("loop.items"), StoryPinType.LIST,
                    StoryRowField("items", node.items, "listOf(1, 2)", code = true) { typed ->
                        edit(node.id, "items") { (it as StoryNode.Loop).copy(items = typed) }
                    },
                )
                exit(StoryPins.BODY, storyLang("loop.body"))
                val item = types[node.id to StoryPins.ITEM]
                row(storyLang("loop.item"), output = StoryPinSpec(StoryPins.ITEM, StoryPinType.ofName(item), item))
                GraphNodeMetrics.WIDTH
            }

            is StoryNode.Code -> {
                flow()
                node.inputs.forEach { (name, written) ->
                    value(
                        node.id,
                        name,
                        name,
                        StoryPinType.ANY,
                        StoryRowField("in-$name", written, code = true) { typed ->
                            edit(node.id, "in-$name") { each ->
                                each as StoryNode.Code
                                each.copy(inputs = each.inputs + (name to typed))
                            }
                        })
                }
                row(
                    field = StoryRowField(
                        "code",
                        node.code,
                        storyLang("code.text"),
                        linesOf(node.code, CODE_COLUMNS),
                        code = true
                    ) { typed ->
                        edit(node.id, "code") { (it as StoryNode.Code).copy(code = typed) }
                    },
                    hint = storyLang("code.help"),
                )
                val read = graph.links.any { it.from == node.id && it.fromPin == StoryPins.VALUE }
                if (read || !graph.index.isInFlow(node.id)) {
                    val type = types[node.id to StoryPins.VALUE]
                    row(
                        storyLang("call.result"),
                        output = StoryPinSpec(StoryPins.VALUE, StoryPinType.ofName(type), type)
                    )
                }
                node.outputs.forEach { (name, type) ->
                    row(
                        name,
                        output = StoryPinSpec(name, StoryPinType.ofName(type), type)
                    )
                }
                CODE_WIDTH
            }

            is StoryNode.Expression -> {
                val type = types[node.id to StoryPins.VALUE]
                row(
                    output = StoryPinSpec(StoryPins.VALUE, StoryPinType.ofName(type), type),
                    field = StoryRowField("code", node.code, storyLang("code.text"), code = true) { typed ->
                        edit(node.id, "code") { (it as StoryNode.Expression).copy(code = typed) }
                    },
                )
                GraphNodeMetrics.WIDTH
            }

            is StoryNode.Pure -> {
                pure(node)
                fitted()
            }

            is StoryNode.Reroute -> GraphNodeMetrics.REROUTE
        }

        private fun option(node: StoryNode.Choose, index: Int, option: StoryOption) {
            fun change(key: String, update: StoryOption.() -> StoryOption) = edit(node.id, key) { each ->
                each as StoryNode.Choose
                each.copy(options = each.options.mapIndexed { at, it -> if (at == index) it.update() else it })
            }
            row(
                output = StoryPinSpec(StoryPins.option(index), StoryPinType.FLOW),
                field = StoryRowField("option-$index", option.text, storyLang("new.option")) { typed ->
                    change("option-$index") { copy(text = typed) }
                },
                onRemove = { state.change { removeOption(node.id, index) } }.takeIf { node.options.size > 1 },
            )
            if (detailed) row(
                storyLang("option.condition"),
                field = StoryRowField("condition-$index", option.condition.orEmpty(), code = true) { typed ->
                    change("condition-$index") { copy(condition = typed.ifBlank { null }) }
                },
                hint = storyLang("option.condition.help"),
            )
        }

        private fun call(written: StoryNode.Call) {
            val node = StoryActionFunctions.resolved(written)
            val signature = StoryCatalog.signatureOf(node)
            flow()
            if (node.block) exit(StoryPins.BODY, storyLang("loop.body"))
            val params = signature?.params.orEmpty().filter { detailed || it.group == null || it.group.keyed }
            fun pin(param: StoryParam) = StoryPinSpec(
                param.name,
                StoryPinType.of(param.type),
                param.type?.let(StoryKotlinTypes::name),
            )
            folded(
                params,
                path = { it.name },
                row = { param ->
                    val pin = pin(param)
                    value(
                        node.id,
                        param.name,
                        StoryCatalog.paramTitle(signature!!, param),
                        pin.type,
                        paramField(node, param),
                        StoryCatalog.paramHint(signature, param),
                        param.optional,
                        pin.kotlin
                    )
                },
                heading = { param, content ->
                    section(sectionKey(node.id, param.name), StoryCatalog.paramTitle(signature!!, param), pin(param), content = content)
                },
            )
            val named = params.mapTo(HashSet()) { it.name }
            node.arguments.keys.filter { it !in named && signature == null }.forEach { name ->
                value(
                    node.id,
                    name,
                    name,
                    StoryPinType.ANY,
                    StoryRowField(name, node.arguments[name].orEmpty(), code = true) { typed ->
                        edit(node.id, name) { each ->
                            each as StoryNode.Call
                            each.copy(arguments = each.arguments + (name to typed))
                        }
                    })
            }
            if (node.returns != null) row(
                storyLang("call.result"),
                output = StoryPinSpec(
                    StoryPins.VALUE,
                    StoryPinType.ofName(node.returns),
                    node.returns
                ),
            )
        }

        private fun paramField(node: StoryNode.Call, param: StoryParam): StoryRowField {
            val written = node.argument(param).orEmpty()
            val form = StoryFieldForm.of(param.type)
            val registry = StoryRegistry.of(StoryPinType.of(param.type))
            val clips = clipHints(node, param)
            return StoryRowField(
                param.name,
                form.shown(written, param.type),
                choices = choicesOf(form, param.choices),
                picks = param.choices.takeIf { form == StoryFieldForm.Code }.orEmpty(),
                color = param.trait<StoryParamTrait.Widget>()?.id == StoryParamTrait.Widget.COLOR,
                code = form != StoryFieldForm.Literal,
                form = form,
                assets = param.trait<StoryParamTrait.Asset>()?.extensions.orEmpty(),
                completions = StoryCompletions.of(param) ?: clips.takeIf { it.isNotEmpty() }?.let { found -> UiCompletionContributor { found } },
                registry = registry,
                slot = param.trait<StoryParamTrait.Widget>()?.id == StoryParamTrait.Widget.INVENTORY_SLOT,
                problem = StoryChecks.param(param, written) ?: clipProblem(node, param, written),
            ) { typed ->
                edit(node.id, param.name) { (it as StoryNode.Call).with(param, form.written(typed, param.type)) }
            }
        }

        /** The clips of the model of the entity [node] acts on, to complete a parameter naming one as it is typed. */
        private fun clipHints(node: StoryNode.Call, param: StoryParam): List<UiTextCompletion> {
            if (!param.has<StoryParamTrait.Animation>()) return emptyList()
            val clips = state.clips.of(node.id) ?: return emptyList()
            return clips.names.orEmpty().map { clip ->
                UiTextCompletion(label = clip, tail = clips.model, wordChars = CLIP_CHARS, preview = ModelClipPreview(clips.model, clip))
            }
        }

        /** What is wrong with the clip [written] names, when the model of the entity [node] acts on is known to lack it. */
        private fun clipProblem(node: StoryNode.Call, param: StoryParam, written: String): String? {
            if (!param.has<StoryParamTrait.Animation>()) return null
            val clip = StoryLiterals.literal(written, param.type) ?: return null
            val clips = state.clips.of(node.id) ?: return null
            val names = clips.names ?: return null
            if (clip in names) return null
            if (names.isEmpty()) return storyLang("problem.animation_none")
            val similar = Spelling.similar(clip, names)
            return if (similar.isEmpty()) {
                storyLang("problem.animation_unknown", clip)
            } else {
                storyLang("problem.animation_similar", clip, similar.joinToString())
            }
        }

        internal fun codeProblem(code: String) = code.takeIf { it.isNotBlank() }?.let(StoryChecks::code)

        private fun pure(node: StoryNode.Pure) {
            val operator = StoryOperators.find(node.operator) ?: return
            val generic = genericOf(node, operator)
            val inputs = StoryOperators.inputsOf(node).map { input -> generic?.let { input.of(it) } ?: input }
            fun kotlinOf(input: StoryOperatorInput) = when {
                input.generic && generic != null -> if (input.takesEmpty) "$generic?" else generic
                else -> input.param?.type?.let(StoryKotlinTypes::name)
            }
            folded(
                inputs.withIndex().toList(),
                path = { it.value.path },
                row = row@{ (index, input) ->
                    if (input.typeName) return@row typeRow(node, operator, input)
                    val field = inputField(node, input)
                    value(
                        node.id,
                        input.name,
                        if (operator.variadic) "${index + 1}" else StoryCatalog.operatorInputTitle(operator, input),
                        input.type,
                        field.takeUnless { input.type in ENTITIES && input.default.isNotEmpty() && field.value.isEmpty() },
                        StoryCatalog.operatorInputHint(operator, input),
                        optional = input.param?.optional == true,
                        kotlin = kotlinOf(input),
                        onRemove = { state.change { removeInput(node.id, index) } }.takeIf { operator.variadic && inputs.size > 1 },
                    )
                },
                heading = { (_, input), content ->
                    val pin = StoryPinSpec(input.name, input.type, kotlinOf(input))
                    section(sectionKey(node.id, input.path), StoryCatalog.operatorInputTitle(operator, input), pin, content = content)
                },
            )
            if (operator.variadic) row(onAdd = { state.change { addInput(node.id) } })
            fun pin(output: StoryOperatorOutput): StoryPinSpec {
                if (output.generic) {
                    val analyzed = types[node.id to output.name]?.takeIf {
                        generic != null && StoryGenerics.elementOf(output.shape, it)?.removeSuffix("?") == generic
                    }
                    val kotlin = analyzed ?: StoryGenerics.shaped(output.shape, generic ?: "T")
                    return StoryPinSpec(output.name, if (generic == null) output.type else StoryPinType.ofName(kotlin), kotlin)
                }
                val named = output.typeOf?.let { node.arguments[it] }?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it?" }
                val kotlin = types[node.id to output.name] ?: named ?: output.kotlin
                return StoryPinSpec(output.name, kotlin?.let(StoryPinType::ofName) ?: output.type, kotlin)
            }
            folded(
                operator.outputs,
                path = { it.path },
                row = { row(StoryCatalog.operatorOutputTitle(operator, it), output = pin(it)) },
                heading = { output, content ->
                    section(
                        sectionKey(node.id, output.path),
                        StoryCatalog.operatorOutputTitle(operator, output),
                        output = pin(output),
                        content = content,
                    )
                },
            )
        }

        /**
         * The type `T` the generic pins of [operator] carry in [node], never missing, since one that may be is what
         * such a pin takes as `T?`.
         */
        private fun genericOf(node: StoryNode.Pure, operator: StoryOperator): String? {
            StoryInsight.generic(state, node.id)?.let { return it.nullable(false).render() }
            val generics = operator.outputs.asSequence().filter { it.generic }
            val given = StoryOperators.inputsOf(node).asSequence().filter { it.generic }.mapNotNull { input ->
                graph.links.firstOrNull { it.to == node.id && it.toPin == input.name }?.let { sources(it.from, it.fromPin) }
            }
            val typed = generics.mapNotNull { output ->
                types[node.id to output.name]?.let { StoryGenerics.elementOf(output.shape, it) }
            }
            val taken = generics.flatMap { output ->
                graph.links.asSequence().filter { it.from == node.id && it.fromPin == output.name }
                    .mapNotNull { link -> consumers(link.to, link.toPin)?.let { StoryGenerics.elementOf(output.shape, it) } }
            }
            return (given + typed + taken).map { it.removeSuffix("?") }.firstOrNull(StoryGenerics::isKnown)
        }

        /** A generic input as one of [type]: of its pin type, and with the field a parameter of the type has. */
        private fun StoryOperatorInput.of(type: String): StoryOperatorInput {
            if (!generic) return this
            val kind = (StoryPinType.classOf(type) ?: TypeOperators.qualified(type)?.let(StoryPinType::classOf))?.starProjectedType
            val param = kind?.let { StoryParam(name, it, traits = StoryParamTrait.choices(StoryCommand.suggestions(it))) }
            return copy(type = StoryPinType.ofName(type), param = param ?: this.param)
        }

        /** The row of [input] of [node] naming a type: a field with the types in reach, and no pin. */
        private fun typeRow(node: StoryNode.Pure, operator: StoryOperator, input: StoryOperatorInput) {
            val written = node.arguments[input.name].orEmpty()
            val field = StoryRowField(input.name, written, TYPE_HINT, typeName = true) { typed ->
                edit(node.id, input.name) { each ->
                    each as StoryNode.Pure
                    each.copy(arguments = if (typed.isBlank()) each.arguments - input.name else each.arguments + (input.name to typed))
                }
            }
            row(StoryCatalog.operatorInputTitle(operator, input), field = field, hint = StoryCatalog.operatorInputHint(operator, input))
        }

        /** The field of [input] of [node]: written as the parameter it passes is, when it passes one. */
        private fun inputField(node: StoryNode.Pure, input: StoryOperatorInput): StoryRowField {
            val written = node.arguments[input.name].orEmpty()
            val param = input.param
            val type = param?.type ?: input.type.type
            val form = StoryFieldForm.of(type)
            return StoryRowField(
                input.name,
                form.shown(written, type),
                form.shown(input.default, type),
                choices = choicesOf(form, param?.choices.orEmpty()),
                picks = param?.choices?.takeIf { form == StoryFieldForm.Code }.orEmpty(),
                code = form != StoryFieldForm.Literal,
                form = form,
                completions = param?.let(StoryCompletions::of) ?: StoryCompletions.of(input.type),
                problem = param?.let { StoryChecks.param(it, written) } ?: codeProblem(written),
                registry = StoryRegistry.of(input.type),
                slot = param?.trait<StoryParamTrait.Widget>()?.id == StoryParamTrait.Widget.INVENTORY_SLOT,
            ) { typed ->
                edit(node.id, input.name) { each ->
                    each as StoryNode.Pure
                    val next = form.written(typed, type)
                    each.copy(arguments = if (next.isBlank()) each.arguments - input.name else each.arguments + (input.name to next))
                }
            }
        }

        /** What a field of [form] offers to put in at once: its own values, or the [suggestions] of its command. */
        private fun choicesOf(form: StoryFieldForm, suggestions: List<String>): List<String> =
            form.choices.ifEmpty { suggestions.takeUnless { form == StoryFieldForm.Code }.orEmpty() }

        private fun linesOf(text: String, columns: Int): Int =
            text.split('\n').sumOf { ceil(it.length.coerceAtLeast(1) / columns.toDouble()).toInt() }
                .coerceIn(MIN_LINES, MAX_LINES)
    }

    private companion object {
        val ENTITIES = setOf(StoryPinType.ENTITY, StoryPinType.PLAYER, StoryPinType.NPC)
        const val CLIP_CHARS = "._-:|/"
        const val TYPE_HINT = "NpcEntity"
        const val PADDING = 6f
        const val SECTION_GAP = 4f
        const val WIDE = 264f
        const val CODE_WIDTH = 320f
        const val LINE = 12f
        const val MIN_LINES = 2
        const val MAX_LINES = 10
        const val SAY_COLUMNS = 44
        const val CODE_COLUMNS = 52
        val FLOW_IN = StoryPinSpec(StoryPins.IN, StoryPinType.FLOW)
        val FLOW_NEXT = StoryPinSpec(StoryPins.NEXT, StoryPinType.FLOW)
    }
}
