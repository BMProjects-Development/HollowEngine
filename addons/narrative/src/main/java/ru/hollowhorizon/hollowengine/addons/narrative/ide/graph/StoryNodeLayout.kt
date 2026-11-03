package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import ru.hollowhorizon.hollowengine.addons.narrative.graph.*
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.addInput
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.removeInput
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.removeOption
import ru.hollowhorizon.hollowengine.addons.narrative.ide.*
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphNodeMetrics
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphPin
import ru.hollowhorizon.hollowengine.client.ui.graph.GraphRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextCompletion
import kotlin.math.ceil
import ru.hollowhorizon.hollowengine.common.utils.Spelling

/** A pin of a row. [kotlin] is the exact type it carries, when [type] alone does not say. */
internal data class StoryPinSpec(val name: String, val type: StoryPinType, val kotlin: String? = null)

/**
 * The value a row edits in place.
 *
 * @param lines how many lines of text it shows; more than one makes it a multi-line field.
 * @param choices the values it takes, when it is one of a known set.
 * @param picks the values a list it holds can be put together from.
 * @param hints what it is usually given, to complete as it is typed.
 * @param typeName whether it holds the name of a Kotlin type.
 * @param color whether it holds a color, which a button next to it picks.
 * @param vector the function that writes it when it is a vector; [value] is then its Kotlin.
 * @param assets the ends of the paths of the files it can be picked from.
 * @param problem what is wrong with what it holds.
 * @param write gets what is typed, as the field shows it.
 */
internal class StoryRowField(
    val key: String,
    val value: String,
    val placeholder: String = "",
    val lines: Int = 1,
    val choices: List<String> = emptyList(),
    val picks: List<String> = emptyList(),
    val hints: List<UiTextCompletion> = emptyList(),
    val typeName: Boolean = false,
    val color: Boolean = false,
    val code: Boolean = false,
    val vector: String? = null,
    val assets: List<String> = emptyList(),
    val problem: String? = null,
    val write: (String) -> Unit,
)

/**
 * A row of a node: a label, the field of what it edits, and the pins on its edges.
 *
 * @param hint what the mark beside the label says.
 * @param optional whether what it holds can be left out.
 */
internal class StoryNodeRow(
    val top: Float,
    val height: Float,
    val label: String? = null,
    val input: StoryPinSpec? = null,
    val output: StoryPinSpec? = null,
    val field: StoryRowField? = null,
    val onRemove: (() -> Unit)? = null,
    val onAdd: (() -> Unit)? = null,
    val hint: String? = null,
    val optional: Boolean = false,
)

/** Where everything of one node sits, in graph units from the corner of the node. */
internal class StoryNodeBox(
    val node: StoryNode,
    val title: String,
    val icon: String?,
    val tag: String,
    val color: UiColor?,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rows: List<StoryNodeRow>,
    val collapsed: Boolean,
) {
    val rect: GraphRect get() = GraphRect(x, y, width, height)

    val pins: List<GraphPin>
        get() {
            if (node is StoryNode.Reroute) return rerouteMiddle(node)
            val middle = y + GraphNodeMetrics.COLLAPSED / 2f
            return rows.flatMap { row ->
                val at = if (collapsed) middle else y + row.top + FLOW_ROW / 2f
                listOfNotNull(
                    row.input?.let { GraphPin(node.id, it.name, false, x, at) },
                    row.output?.let { GraphPin(node.id, it.name, true, x + width, at) },
                )
            }
        }

    /** A reroute is a dot, and a link goes in and comes out of its middle. */
    private fun rerouteMiddle(reroute: StoryNode.Reroute): List<GraphPin> {
        val atX = x + width / 2f
        val atY = y + height / 2f
        return listOf(
            GraphPin(node.id, reroute.input, false, atX, atY),
            GraphPin(node.id, reroute.output, true, atX, atY)
        )
    }

    private fun spec(pin: GraphPin) =
        rows.firstNotNullOfOrNull { row -> (if (pin.output) row.output else row.input)?.takeIf { it.name == pin.name } }

    fun type(pin: GraphPin): StoryPinType? = spec(pin)?.type

    /** The exact type of what [pin] carries, when that is known. */
    fun kotlin(pin: GraphPin): String? = spec(pin)?.kotlin

    companion object {
        const val FLOW_ROW = GraphNodeMetrics.FIELD_ROW
    }
}

/** The color of the pins of a type and of the links out of them; the pin rules of `story-graph.hss` use the same. */
internal fun StoryPinType.color(): UiColor = UiColor.fromArgb(
    when (this) {
        StoryPinType.FLOW -> 0xFFE6E8EE
        StoryPinType.TEXT -> 0xFFE89AC0
        StoryPinType.NUMBER -> 0xFF44BC74
        StoryPinType.BOOL -> 0xFFD9605A
        StoryPinType.VECTOR -> 0xFFF0CF60
        StoryPinType.ENTITY -> 0xFF4FA8E0
        StoryPinType.NPC -> 0xFF4FD0D0
        StoryPinType.PLAYER -> 0xFF6F8CF0
        StoryPinType.LIST -> 0xFFC9874A
        StoryPinType.MAP -> 0xFFB48EE0
        StoryPinType.OBJECT -> 0xFF8FA0B8
        StoryPinType.ANY -> 0xFFA5ABC0
    }.toInt(),
)

internal fun StoryPinType.tag(): String = "gn-type-${name.lowercase()}"

/** Lays out the nodes of a story with the fields that edit them in [state]. */
internal class StoryNodeLayout(private val graph: StoryGraph, private val state: StoryEditorState) {
    private val linked = graph.index.values.keys
    private val types = StoryInsight.analysis(state, graph).types

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
            if (collapsed) GraphNodeMetrics.COLLAPSED else rows.bottom(), rows.result, collapsed,
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
    private inner class Rows(private val detailed: Boolean) {
        val result = ArrayList<StoryNodeRow>()
        private var y = GraphNodeMetrics.HEADER + PADDING

        fun bottom() = y + PADDING

        private fun row(
            label: String? = null,
            input: StoryPinSpec? = null,
            output: StoryPinSpec? = null,
            field: StoryRowField? = null,
            onRemove: (() -> Unit)? = null,
            onAdd: (() -> Unit)? = null,
            hint: String? = null,
            optional: Boolean = false,
        ) {
            val height = if (field != null && field.lines > 1) field.lines * LINE + 12f else StoryNodeBox.FLOW_ROW
            result += StoryNodeRow(y, height, label, input, output, field, onRemove, onAdd, hint, optional)
            y += height
        }

        private fun flow(hasInput: Boolean = true, hasOutput: Boolean = true) = row(
            input = FLOW_IN.takeIf { hasInput },
            output = FLOW_NEXT.takeIf { hasOutput },
        )

        private fun exit(pin: String, label: String) = row(label = label, output = StoryPinSpec(pin, StoryPinType.FLOW))

        /** A value pin with what it holds when nothing is linked to it. */
        private fun value(
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
                row(
                    output = FLOW_NEXT,
                    field = StoryRowField("name", node.name, storyLang("start.name")) { typed ->
                        edit(node.id, "name") { (it as StoryNode.Start).copy(name = typed.trim()) }
                    },
                )
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
                val kotlin = written?.let(StoryPinType::shortName)
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
                val helper = StoryLiterals.vectorHelper(type.type)
                value(
                    node.id, StoryPins.VALUE, storyLang("set.value"), type,
                    StoryRowField(
                        "value", node.value, "0", code = true, vector = helper,
                        problem = codeProblem(node.value),
                    ) { typed ->
                        edit(node.id, "value") { (it as StoryNode.Assign).copy(value = typed) }
                    },
                    kotlin = kotlin,
                )
                row(
                    storyLang("set.result"),
                    output = StoryPinSpec(StoryPins.VALUE, type, kotlin),
                    hint = storyLang("set.result.help")
                )
                if (helper != null) WIDE else GraphNodeMetrics.WIDTH
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
                if (StoryOperators.inputsOf(node)
                        .any { it.type == StoryPinType.VECTOR }
                ) WIDE else GraphNodeMetrics.WIDTH
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
            flow()
            if (node.block) exit(StoryPins.BODY, storyLang("loop.body"))
            val signature = StoryCatalog.signatureOf(node)
            val params = signature?.params.orEmpty().filter { detailed || it.group == null }
            params.forEach { param ->
                val label = StoryCatalog.paramTitle(signature!!, param)
                val type = StoryPinType.of(param.type)
                val kotlin = param.type?.let(StoryKotlinTypes::name)?.let(StoryPinType::shortName)
                value(
                    node.id,
                    param.name,
                    label,
                    type,
                    paramField(node, param),
                    StoryCatalog.paramHint(signature, param),
                    param.optional,
                    kotlin
                )
            }
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
                    StoryPinType.shortName(node.returns)
                ),
            )
        }

        private fun paramField(node: StoryNode.Call, param: StoryParam): StoryRowField {
            val written = node.argument(param).orEmpty()
            val helper = StoryLiterals.vectorHelper(param.type)
            val kotlin = StoryLiterals.isKotlin(param.type)
            return StoryRowField(
                param.name,
                if (helper != null || kotlin) written else StoryLiterals.text(written, param.type),
                choices = param.suggestions.takeUnless { kotlin }.orEmpty(),
                picks = param.suggestions.takeIf { kotlin }.orEmpty(),
                color = param.widget == "color",
                code = kotlin,
                vector = helper,
                assets = param.assets,
                hints = clipHints(node, param),
                problem = StoryChecks.param(param, written) ?: clipProblem(node, param, written),
            ) { typed ->
                val next = if (helper != null || kotlin) typed else StoryLiterals.expression(typed, param.type)
                edit(node.id, param.name) { (it as StoryNode.Call).with(param, next) }
            }
        }

        /** The clips of the model of the entity [node] acts on, to complete a parameter naming one as it is typed. */
        private fun clipHints(node: StoryNode.Call, param: StoryParam): List<UiTextCompletion> {
            if (param.meaning != StoryParamMeaning.ANIMATION) return emptyList()
            val clips = state.clips.of(node.id) ?: return emptyList()
            return clips.names.orEmpty().map { UiTextCompletion(label = it, tail = clips.model, wordChars = CLIP_CHARS) }
        }

        /** What is wrong with the clip [written] names, when the model of the entity [node] acts on is known to lack it. */
        private fun clipProblem(node: StoryNode.Call, param: StoryParam, written: String): String? {
            if (param.meaning != StoryParamMeaning.ANIMATION) return null
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

        private fun codeProblem(code: String) = code.takeIf { it.isNotBlank() }?.let(StoryChecks::code)

        private fun pure(node: StoryNode.Pure) {
            val operator = StoryOperators.find(node.operator) ?: return
            val inputs = StoryOperators.inputsOf(node)
            inputs.forEachIndexed { index, input ->
                val written = node.arguments[input.name].orEmpty()
                val helper = StoryLiterals.vectorHelper(input.type.type)
                val shown = if (helper != null) written else input.type.text(written)
                val label = if (operator.variadic) "${index + 1}" else StoryCatalog.operatorPinTitle(input.name)
                val field = StoryRowField(
                    input.name,
                    shown,
                    input.type.text(input.default),
                    vector = helper,
                    hints = StoryCatalog.inputHints(input.name),
                    problem = codeProblem(written),
                ) { typed ->
                    edit(node.id, input.name) { each ->
                        each as StoryNode.Pure
                        val next = if (helper != null) typed else input.type.expression(typed)
                        each.copy(arguments = if (next.isBlank()) each.arguments - input.name else each.arguments + (input.name to next))
                    }
                }
                value(
                    node.id, input.name, label, input.type,
                    field.takeUnless { input.type == StoryPinType.ENTITY && input.default.isNotEmpty() && shown.isEmpty() },
                    StoryCatalog.operatorPinHint(operator.id, input.name),
                    onRemove = {
                        state.change {
                            removeInput(
                                node.id,
                                index
                            )
                        }
                    }.takeIf { operator.variadic && inputs.size > 1 },
                )
            }
            if (operator.variadic) row(onAdd = { state.change { addInput(node.id) } })
            operator.outputs.forEach { output ->
                val kotlin = types[node.id to output.name] ?: output.kotlin
                val type = kotlin?.let(StoryPinType::ofName) ?: output.type
                row(StoryCatalog.operatorPinTitle(output.name), output = StoryPinSpec(output.name, type, kotlin))
            }
        }

        private fun linesOf(text: String, columns: Int): Int =
            text.split('\n').sumOf { ceil(it.length.coerceAtLeast(1) / columns.toDouble()).toInt() }
                .coerceIn(MIN_LINES, MAX_LINES)
    }

    private companion object {
        const val CLIP_CHARS = "._-:|/"
        const val PADDING = 6f
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
