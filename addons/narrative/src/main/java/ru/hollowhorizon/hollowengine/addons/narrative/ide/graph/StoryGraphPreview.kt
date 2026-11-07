package ru.hollowhorizon.hollowengine.addons.narrative.ide.graph

import androidx.compose.runtime.*
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphEdits.withGroupChanged
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNodeGroup
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryPoint
import ru.hollowhorizon.hollowengine.addons.narrative.ide.*
import ru.hollowhorizon.hollowengine.addons.narrative.story.TypeOperators
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.graph.*
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdeFilePreview
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewContext
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewCrumb
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewError
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdeViewMode
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.*

private const val CanvasId = "story-graph"
private const val StoryGraphStylesheet = "hollowengine-narrative:ui/styles/story-graph.hss"
private const val BackIcon = "hollowengine:textures/gui/icons/graph/back.svg"
private const val ColumnStep = 340f
private const val RowStep = 170f
private const val RemoveIcon = "hollowengine:textures/gui/icons/remove.svg"
private const val AddIcon = "hollowengine:textures/gui/icons/add.svg"

/** The `.story` file as nodes and the links between them. */
internal val StoryGraphFilePreview = HollowIdeFilePreview(
    id = "story-graph",
    defaultMode = HollowIdeViewMode.PREVIEW,
    icon = "hollowengine:textures/gui/icons/files/story.svg",
    title = "hollowengine.gui.ide.story.graph",
    matcher = { it.endsWith(".story") },
) { StoryGraphPreview(it) }

internal class StoryGraphState : StoryEditorState() {
    /** The view and selection of the story and of each function, kept apart: going inside one leaves the rest as it was. */
    private val editors = HashMap<String?, GraphEditorState>()

    val editor: GraphEditorState get() = editors.getOrPut(scope) { GraphEditorState() }

    /** How big the canvas is, for bringing a node to its middle. */
    var canvas = UiRect.Zero

    /** Where a drag has taken nodes, until it ends and writes that to the file. */
    var moved by mutableStateOf(emptyMap<String, StoryPoint>())

    /** The node to select and bring into sight once the editor shows the graph it is in. */
    var reveal by mutableStateOf<String?>(null)
}

@Composable
internal fun StoryGraphPreview(context: HollowIdePreviewContext) {
    val state = context.state { StoryGraphState() }
    state.context = context
    val parsed = state.model.read(context.text)
    val root = parsed.getOrNull() ?: return HollowIdePreviewError(parsed.exceptionOrNull())
    val scope = state.scope.takeIf { root.scope(it) != null }
    SideEffect { if (state.scope != scope) state.scope = scope }
    val graph = root.scope(scope) ?: root
    val controller = remember(state, scope) { GraphEditorController(CanvasId, state.editor) }
    val arranged = remember(graph) { arrange(graph) }
    val ownChecks = remember(root) { StoryChecks.all(root) }
    val analysis = StoryInsight.analysis(state, root)
    val checks = ownChecks + analysis.checks
    val problems = remember(checks) { checks.filter { it.node != null }.groupBy { it.node!! } }
    state.clips = rememberStoryClips(graph)
    val boxes = remember(graph, analysis, state.clips, state.openSections, TypeOperators.revision) { StoryGraphBoxes(graph, state) }
    val model = StoryGraphEditorModel(state, graph, boxes, arranged + state.moved) { controller.select(it) }
    controller.model = model

    val owners = remember(root) { root.owners() }
    PublishStoryProblems(context, root.flattened(), checks) { node ->
        state.scope = owners[node]?.id
        state.reveal = node
    }
    SideEffect {
        val node = state.reveal ?: return@SideEffect
        val rect = model.all[node]?.rect ?: return@SideEffect
        state.reveal = null
        controller.select(GraphSelection.of(node))
        state.editor.view.centerOn(rect.centerX, rect.centerY, state.canvas.width, state.canvas.height)
    }
    PublishStoryCrumbs(context, state, root.functions.firstOrNull { it.id == scope })

    PublishStorySelection(context, state, model)

    Box(
        mode = UiBoxMode.STACK,
        tags = listOf("ide-file-panel", "story-editor-root"),
        modifier = Modifier.size(100.percent, 100.percent).style(InspectorStylesheet).style(StoryEditorStylesheet)
            .style(GraphNodesStylesheet).style(StoryGraphStylesheet).focusScope().onPlaced { state.canvas = it },
    ) {
        GraphEditorCanvas(controller, Modifier.size(100.percent, 100.percent)) {
            GraphFrameHeaders(controller)
            model.boxes.values.forEach { box ->
                key(box.node.id) {
                    // A node out of sight keeps its key: one taken out of the list makes Compose move every node after it.
                    if (box.node.id in controller.shown) {
                        StoryNodeView(controller, boxes, state, box, problems[box.node.id])
                    }
                }
            }
            model.scene.cards.forEach { card ->
                key("card-${card.id}") {
                    GraphGroupCard(
                        controller, card,
                        pinName = { it.name },
                        ownerName = { model.all[it.node]?.title ?: it.node },
                        pinTags = { listOf(model.typeOf(it).tag()) },
                    )
                }
            }
        }
        if (scope != null) ScopeBack(state)
        state.codeField?.let { StoryCodeDialog(state, it) }
        state.colorField?.let { StoryColorDialog(state, it) }
        state.slotField?.let { StorySlotDialog(state, it) }
    }
}

/** The button in the corner of a function's body that leads back out to the story. */
@Composable
private fun ScopeBack(state: StoryGraphState) {
    Box(
        id = "st-scope-back",
        tags = listOf("st-scope-back"),
        modifier = Modifier.position(8.px, 8.px).input(hoverable = true, clickable = true)
            .tooltipOnHover(storyLang("function.back")).onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) state.scope = null
                event.consume()
            },
    ) {
        Image(BackIcon, tags = listOf("st-scope-back-icon"))
    }
}

/** Tells the status bar which function the editor is inside of; its first step leads back out to the story. */
@Composable
private fun PublishStoryCrumbs(context: HollowIdePreviewContext, state: StoryGraphState, function: StoryNode.Function?) {
    SideEffect {
        context.publishCrumbs(
            if (function == null) emptyList() else listOf(
                HollowIdePreviewCrumb(storyLang("story"), StoryNodeKind.START.icon) { state.scope = null },
                HollowIdePreviewCrumb(function.name, StoryNodeKind.FUNCTION.icon),
            ),
        )
    }
    DisposableEffect(context) { onDispose { context.publishCrumbs(emptyList()) } }
}

/** Keeps the inspector on what is selected; apart from the graph, which a click has no need to make anew. */
@Composable
private fun PublishStorySelection(
    context: HollowIdePreviewContext,
    state: StoryGraphState,
    model: StoryGraphEditorModel,
) {
    val selection = state.editor.selection
    val selected = selection.single?.takeIf { selection.group == null && it in model.all }
    SideEffect {
        if (state.selected?.node != selected) state.selected = selected?.let { StorySelection(it) }
    }
    PublishInspector(source = "story-graph:${context.file.path}", key = selection.group to selected) {
        selection.group?.let { groupInspector(state, it) } ?: storyInspector(
            state,
            selected?.let { StorySelection(it) })
    }
}

/** A node on the canvas with a row per thing it holds, each edited in place. */
@Composable
private fun StoryNodeView(
    controller: GraphEditorController,
    boxes: StoryGraphBoxes,
    state: StoryGraphState,
    box: StoryNodeBox,
    problems: List<StoryCheck>?,
) {
    val node = box.node
    if (node is StoryNode.Reroute) {
        val output = box.pins.first { it.output }
        return GraphRerouteView(controller, node.id, box.rect, output, listOf(boxes.typeOf(output).tag()))
    }
    val problem = problems?.map { it.message }?.distinct()?.joinToString("\n")
    val warning = problems?.all { it.warning } == true
    GraphNodeFrame(
        controller, node.id, box.rect,
        listOfNotNull(
            "gn-node",
            box.tag,
            "collapsed".takeIf { box.collapsed },
            (if (warning) "warning" else "error").takeIf { problem != null }),
    ) {
        val open = state.functionInside(node)?.let { target ->
            GraphNodeOpen(storyLang("function.enter")) { state.scope = target }
        }
        GraphNodeHeader(node.id, box.title, box.icon, box.width, box.collapsed, problem, box.color, warning, open) {
            state.change { copy(collapsed = if (node.id in collapsed) collapsed - node.id else collapsed + node.id) }
        }
        if (!box.collapsed) box.rows.filterNot { it.hidden }.forEach { row -> StoryRowView(controller, state, box, row) }
        box.shownPins.forEach { pin ->
            GraphPinView(
                controller, pin, box.x, box.y, boxes.isLinked(pin), listOf(boxes.typeOf(pin).tag()), boxes.tooltip(pin),
                UiColor.fromArgb(boxes.typeOf(pin).color),
            )
        }
    }
}

@Composable
private fun StoryRowView(
    controller: GraphEditorController,
    state: StoryGraphState,
    box: StoryNodeBox,
    row: StoryNodeRow,
) {
    row.section?.let { return SectionRowView(box, row, it) }
    val readOnly = state.context?.readOnly == true
    Row(
        tags = listOfNotNull("gn-node-row", "st-nested".takeIf { row.depth > 0 }),
        modifier = Modifier.rowPlace(box, row).alignItems(vertical = UiAlign.CENTER),
    ) {
        val field = row.field
        val insight = field?.let { fieldInsight(state, box.node.id, it) }
        val problems =
            field?.let { (listOfNotNull(it.problem) + insight!!.diagnostics.map { found -> found.message }).distinct() }
                .orEmpty()
        val problem = problems.takeIf { it.isNotEmpty() }?.joinToString("\n")
        val wrong =
            field?.problem != null || insight?.diagnostics?.any { it.severity == UiTextDiagnosticSeverity.ERROR } == true
        row.nameField?.let { name ->
            TextField(
                value = name.value,
                onChange = name.write,
                readOnly = readOnly,
                id = "st-field-${box.node.id}-${name.key}",
                tags = listOf("gn-field"),
                modifier = Modifier.size(0.px, 20.px).grow(1f).graphField(controller, box.node.id),
            )
        }
        if (row.label != null && field == null && row.input == null) Box(modifier = Modifier.size(0.px, 1.px).grow(1f))
        row.label?.let {
            Text(
                it,
                tags = listOfNotNull(
                    "gn-pin-label",
                    "output".takeIf { row.input == null && field == null },
                    "optional".takeIf { row.optional })
            )
        }
        row.hint?.let { FieldHelp(it) }
        if (field != null) when {
            field.choices.isNotEmpty() -> ChoiceField(controller, box, field)
            field.vector != null -> StoryVectorField(
                "st-field-${box.node.id}-${field.key}",
                field.value,
                field.vector,
                readOnly,
                problem,
                listOf("gn-field"),
                Modifier.size(0.px, 20.px).grow(1f),
                fieldModifier = Modifier.graphField(controller, box.node.id),
                write = field.write,
            )

            else -> FieldWithAsset(row, field) {
                TextField(
                    value = field.value,
                    onChange = field.write,
                    mode = if (field.lines > 1) UiTextFieldMode.MULTI_LINE else UiTextFieldMode.SINGLE_LINE,
                    readOnly = readOnly,
                    placeholder = field.placeholder,
                    insight = insight!!.copy(diagnosticTooltips = false),
                    id = "st-field-${box.node.id}-${field.key}",
                    tags = listOfNotNull(
                        "gn-field",
                        "gn-expression".takeIf { field.code },
                        (if (wrong) "error" else "warning").takeIf { problem != null }),
                    modifier = Modifier.size(0.px, if (field.lines > 1) (row.height - 6f).px else 20.px).grow(1f)
                        .tooltipOnHover(problem.orEmpty()).graphField(controller, box.node.id),
                )
            }
        }
        val own = "${box.node.id}-${field?.key}"
        if (field != null && field.picks.isNotEmpty()) PicksButton(controller, own, field)
        if (field != null && field.color) ColorButton(state, own, field)
        field?.registry?.let { RegistryButton(controller, own, it, field) }
        if (field != null && field.slot) SlotButton(state, own, field)
        if (field != null && field.code && field.vector == null) CodeEditorButton(
            state,
            box.node.id,
            row.label ?: field.placeholder,
            field
        )
        row.onRemove?.let { remove ->
            Image(
                RemoveIcon,
                id = "st-remove-${box.node.id}-${row.top}",
                tags = listOf("st-row-button"),
                modifier = Modifier.input(hoverable = true, clickable = true).onPress { event ->
                    if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !readOnly) remove()
                    event.consume()
                },
            )
        }
        row.onAdd?.let { add ->
            Row(
                id = "st-add-${box.node.id}",
                tags = listOf("st-row-add"),
                modifier = Modifier.input(hoverable = true, clickable = true).alignItems(vertical = UiAlign.CENTER)
                    .onPress { event ->
                        if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT && !readOnly) add()
                        event.consume()
                    },
            ) {
                Image(AddIcon, tags = listOf("st-row-button"))
                Text(row.addLabel ?: storyLang("choice.add"), tags = listOf("gn-pin-hint"))
            }
        }
    }
}

/** A heading that folds the rows under it: a click anywhere on it shows or hides them. */
@Composable
private fun SectionRowView(box: StoryNodeBox, row: StoryNodeRow, section: StoryRowSection) {
    Row(
        id = "st-section-${box.node.id}-${row.top}",
        tags = listOfNotNull("gn-node-row", "st-section", "open".takeIf { section.open }),
        modifier = Modifier.rowPlace(box, row).alignItems(vertical = UiAlign.CENTER)
            .input(hoverable = true, clickable = true).onPress { event ->
                if (event.button == GLFW.GLFW_MOUSE_BUTTON_LEFT) section.toggle()
                event.consume()
            },
    ) {
        DisclosureArrow(section.open)
        Text(row.label.orEmpty(), tags = listOf("gn-pin-label", "st-section-title"))
    }
}

/**
 * Where [row] of [box] sits: across the node, and inside a section on a band set in from its edges, its content
 * indented by how deep the section is.
 */
private fun Modifier.rowPlace(box: StoryNodeBox, row: StoryNodeRow): Modifier {
    if (row.depth == 0 && row.section == null) {
        return position(0.px, row.top.px).size(box.width.px, row.height.px).padding(GraphNodeMetrics.INSET.px, 0.px)
    }
    val inset = GraphNodeMetrics.INSET - SECTION_BAND + row.depth * SECTION_INDENT
    return position(SECTION_BAND.px, row.top.px).size((box.width - 2 * SECTION_BAND).px, row.height.px)
        .padding(inset.px, 0.px, (GraphNodeMetrics.INSET - SECTION_BAND).px, 0.px)
}

/** How far the band of a section is set in from the edges of its node. */
private const val SECTION_BAND = 4f

/** How far the rows of a section are indented per level. */
private const val SECTION_INDENT = 8f

/** What the text field of [field] of the node [owner] offers, by what it holds. */
internal fun fieldInsight(state: StoryEditorState, owner: String, field: StoryRowField): UiCodeInsight {
    val analyzed = StoryInsight.field(state, StoryFieldKey(owner, field.key), field.value)
    return when {
        field.code -> analyzed
        field.typeName -> StoryInsight.type(state)
        field.completions != null -> UiCodeInsight(completions = field.completions)
        else -> UiCodeInsight(diagnostics = analyzed.diagnostics.map { it.copy(start = 0, end = 0) })
    }
}

/** [content], the field of [field], with the button that picks a file for it when it names one. */
@Composable
private fun FieldWithAsset(row: StoryNodeRow, field: StoryRowField, content: @Composable () -> Unit) {
    content()
    if (field.assets.isNotEmpty()) {
        AssetPickerButton(row.label.orEmpty(), field.value, { it.assets(field.assets) }, field.write)
    }
}

/** A value out of a known set: a press opens the values, and the field keeps anything else that was typed. */
@Composable
private fun ChoiceField(controller: GraphEditorController, box: StoryNodeBox, field: StoryRowField) {
    Text(
        field.value.ifEmpty { "—" },
        id = "st-choice-${box.node.id}-${field.key}",
        tags = listOf("gn-chip"),
        modifier = Modifier.size(0.px, UiLength.Auto).grow(1f).input(hoverable = true, clickable = true)
            .onPress { event ->
                if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onPress
                controller.menu = GraphMenu(
                    event.x, event.y,
                    field.choices.map { value ->
                        UiDropdownItem(
                            value,
                            mark = UiDropdownMark.RADIO,
                            checked = value == field.value
                        ) { field.write(value) }
                    },
                )
                event.consume()
            },
    )
}

/** The inspector of a group: what it is called and how it looks. */
private fun groupInspector(state: StoryEditorState, id: String): InspectorTarget? {
    val group = state.read()?.groups?.firstOrNull { it.id == id } ?: return null
    return InspectorTarget(
        id = "story-group-$id",
        title = graphEditorText("group"),
        subtitle = group.id,
        history = state.context?.history
    ) {
        state.read()?.groups?.firstOrNull { it.id == id }?.let { GroupFields(state, it) }
    }
}

@Composable
private fun GroupFields(state: StoryEditorState, group: StoryNodeGroup) {
    fun change(update: (StoryNodeGroup) -> StoryNodeGroup) =
        state.change(mergeKey = "group/${group.id}") { withGroupChanged(group.id, update) }
    Section(graphEditorText("group")) {
        TextRow(storyLang("group.title"), group.title, id = "st-group-title-${group.id}") { title ->
            change {
                it.copy(
                    title = title
                )
            }
        }
        Label(storyLang("group.color"))
        val current = group.color.takeIf { it in GraphGroupColors.all } ?: GraphGroupColors.all.keys.first()
        Pills(
            GraphGroupColors.all.keys.toList(),
            current,
            { storyLang("color.$it") }) { color -> change { it.copy(color = color) } }
        ToggleRow(
            graphEditorText("collapse_group"),
            group.collapsed
        ) { collapsed -> change { it.copy(collapsed = collapsed) } }
    }
}

/** Every node with its place: as the file says, or by how far flow is from where it starts for those without one. */
private fun arrange(graph: StoryGraph): Map<String, StoryPoint> {
    val depth = HashMap<String, Int>()
    val queue = ArrayDeque(graph.nodes.filter { it is StoryNode.Start || it is StoryNode.Parameters }.map { it.id to 0 })
    while (queue.isNotEmpty()) {
        val (id, at) = queue.removeFirst()
        if (id in depth) continue
        depth[id] = at
        graph.index.flowOut(id).forEach { queue += it to at + 1 }
    }
    val rows = HashMap<Int, Int>()
    return graph.nodes.associate { node ->
        val column = depth[node.id] ?: 0
        val row = rows.merge(column, 1, Int::plus)!! - 1
        node.id to (graph.layout[node.id] ?: StoryPoint(column * ColumnStep, row * RowStep))
    }
}
