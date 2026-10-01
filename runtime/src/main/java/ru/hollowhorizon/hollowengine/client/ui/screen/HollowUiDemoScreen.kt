package ru.hollowhorizon.hollowengine.client.ui.screen

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.docking.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.UiBackfaceVisibility
import ru.hollowhorizon.hollowengine.client.ui.widgets.Video
import ru.hollowhorizon.hollowengine.client.utils.mc
import ru.hollowhorizon.hollowengine.client.utils.lang

class HollowUiDemoScreen : HollowComposeUiScreen("Hollow UI Demo", DemoStyles) {
    private var selectedTab by mutableStateOf("overview")
    private var popupTooltipVisible by mutableStateOf(false)
    private var popupAnchorExpanded by mutableStateOf(false)
    private var popupAnchorBounds by mutableStateOf(UiRect.Zero)
    private val freeNodeOffsets = mutableStateMapOf<Int, DemoOffset>()
    private var layoutGlassOffset by mutableStateOf(DemoOffset.Zero)
    private var editorKeyLog by mutableStateOf("F2 is handled by Modifier.onKeyInput")
    private var textLabWidth by mutableStateOf(300f)
    private var textLabWrap by mutableStateOf(true)
    private var textLabEffects by mutableStateOf(true)
    private var textLabAlign by mutableStateOf(UiTextAlign.LEFT)
    private val dockingState = DockingState().apply {
        open(DockItem("project", "Project"))
        open(DockItem("editor", "Editor"))
        open(DockItem("preview", "Preview"), DockTarget(placement = DockPlacement.RIGHT))
        open(DockItem("console", "Console"), DockTarget(placement = DockPlacement.BOTTOM))
        openFloating(DockItem("inspector", "Inspector"), x = 430f, y = 72f, width = 260f, height = 190f)
    }

    override fun pipelineFrames(): Boolean {
        return true
    }

    @Composable
    override fun Content() {
        Column(id = "demo-root") {
            Row(id = "tabs", tags = listOf("tabs"), modifier = Modifier.scrollable()) {
                tab("overview", "Главная", "hollowengine:textures/gui/npc_menu/talk.png")
                tab("widgets", "Виджеты", "hollowengine:textures/gui/npc_menu/quests.png")
                tab("text", "Text", "hollowengine:textures/gui/icons/info.svg")
                tab("textlab", "TextLab", "hollowengine:textures/gui/icons/info.svg")
                tab("inlineflow", "Flow", "hollowengine:textures/gui/icons/info.svg")
                tab("editor", "Editor", "hollowengine:textures/gui/icons/code_editor.svg")
                tab("layout", "Разметка", "hollowengine:textures/gui/npc_menu/trade.png")
                tab("docking", "Docking", "hollowengine:textures/gui/icons/code_editor.svg")
                tab("effects", "Эффекты", "hollowengine:textures/gui/npc_menu/character.png")
                tab("shapes", "Shapes", "hollowengine:textures/gui/icons/code_editor.svg")
                tab("particles", "hollowengine.gui.particles.title".lang, "hollowengine:textures/gui/npc_menu/character.png")
                tab("video", "Видео", "hollowengine:textures/gui/icons/play.svg")
            }
            Box(id = "content", tags = listOf("content")) {
                when (selectedTab) {
                    "widgets" -> widgets()
                    "text" -> textAndPopupDemo()
                    "textlab" -> textLab()
                    "inlineflow" -> inlineFlowLab()
                    "editor" -> editorDemo()
                    "layout" -> layout()
                    "docking" -> docking()
                    "effects" -> effects()
                    "shapes" -> shapesDemo()
                    "particles" -> particlesDemo()
                    "video" -> videoDemo()
                    else -> overview()
                }
            }
        }
    }

    override fun rebuildEveryFrame(): Boolean = selectedTab == "transforms" || selectedTab == "effects"

    @Composable
    private fun tab(id: String, label: String, icon: String) {
        Row(
            id = "tab-$id", tags = listOf("tab"), modifier = Modifier
                .input(hoverable = true, clickable = true)
                .onClick {
                    selectedTab = id
                }
        ) {
            Image(icon, tags = listOf("tab-icon"))
            Text(label, tags = listOf("tab-label"))
        }
    }

    @Composable
    private fun overview() {
        Column(tags = listOf("panel", "scroll-panel"), modifier = Modifier.scrollable()) {
            Text("Какой-то интерфейс", tags = listOf("title"))
            Text(
                "Здесь могла быть ваша реклама, но у вас нет денег :)",
                tags = listOf("body")
            )
            repeat(18) { index ->
                Row(tags = listOf("row")) {
                    Image("hollowengine:textures/gui/quests/quest_icon.png", tags = listOf("small-icon"))
                    Text(
                        "Какая-то фигня для прокрутки под номером ${index + 1}.",
                        tags = listOf("body")
                    )
                }
            }
        }
    }

    @Composable
    private fun widgets() {
        Box(tags = listOf("panel-grid"), modifier = Modifier.scrollable()) {
            Column(tags = listOf("card"), modifier = Modifier.position(0.px, 0.px)) {
                Text("Текст", tags = listOf("card-title"))
                Text(
                    "Не ожидали? Да, это реально текст и в нём правда есть буквы! И их тут многа...",
                    tags = listOf("body")
                )
            }
            Column(tags = listOf("card"), modifier = Modifier.position(184.px, 0.px)) {
                Text("Картинка", tags = listOf("card-title"))
                Image("hollowengine:textures/gui/npc_menu/character.png", tags = listOf("preview-image"))
            }
            Column(tags = listOf("card"), modifier = Modifier.position(368.px, 0.px)) {
                Text("Предмет", tags = listOf("card-title"))
                Item("minecraft:diamond_block", tags = listOf("item-preview"))
            }
            Column(tags = listOf("card"), modifier = Modifier.position(0.px, 136.px)) {
                Text("Сущность", tags = listOf("card-title"))
                mc.player?.let { Entity(it, tags = listOf("entity-preview")) }
            }
            Column(tags = listOf("card"), modifier = Modifier.position(184.px, 136.px)) {
                Text("Холст", tags = listOf("card-title"))
                Box(tags = listOf("visual-preview"))
            }
        }
    }

    @Composable
    private fun textAndPopupDemo() {
        Box(tags = listOf("text-demo-stage"), modifier = Modifier.scrollable()) {
            Column(tags = listOf("text-demo-card"), modifier = Modifier.position(0.px, 0.px)) {
                Text("Inline widget", tags = listOf("card-title"))
                Text(
                    tags = listOf("text-demo-copy"),
                    modifier = Modifier.size(320.px, 64.px).textAlign(UiTextAlign.JUSTIFY),
                ) {
                    Span("Inline text can host ")
                    InlineWidget(
                        "inline-chip",
                        tags = listOf("text-inline-chip"),
                        modifier = Modifier.align(vertical = UiAlign.CENTER),
                    ) {
                        Text(
                            "AUTO",
                            tags = listOf("text-inline-chip-label"),
                            modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER),
                        )
                    }
                    Span(" measured widgets and keep wrapping/justify consistent.")
                }
            }

            Column(tags = listOf("text-demo-card", "text-slot-card"), modifier = Modifier.position(352.px, 0.px)) {
                Text("Inline slot", tags = listOf("card-title"))
                Text(
                    tags = listOf("text-demo-copy"),
                    modifier = Modifier.size(330.px, 116.px),
                ) {
                    InlineWidget(
                        "inline-note",
                        tags = listOf("text-slot-note"),
                        modifier = Modifier.align(vertical = UiAlign.START),
                    ) {
                        Text("Measured\nslot", tags = listOf("text-slot-note-label"))
                    }
                    Span("Measured slots now travel through the same inline line builder as text and images, so wrapping uses one layout path.")
                }
            }

            Column(tags = listOf("text-demo-card", "textflow-card"), modifier = Modifier.position(352.px, 164.px)) {
                Text("Rich text (Text {})", tags = listOf("card-title"))
                Text(tags = listOf("textflow-para")) {
                    Span("Text{} flows spans, a ")
                    // A nested Text{} is an inline group: its spans flatten into this line breaking
                    // while its padded rounded background is sliced continuously across wrap points.
                    Text(tags = listOf("group-slice")) {
                        Span("sliced group whose highlight stays continuous across wrapped lines")
                    }
                    Span(", a ")
                    Text(tags = listOf("group-clone")) {
                        Span("cloned group")
                    }
                    Box(mode = UiBoxMode.STACK, tags = listOf("textflow-chip")) {
                        Text(
                            "chip",
                            tags = listOf("textflow-chip-label"),
                            modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER),
                        )
                    }
                    Span(" and a live ")
                    Caret(tags = listOf("textflow-caret"))
                }
            }

            Column(tags = listOf("text-demo-card", "popup-demo-card"), modifier = Modifier.position(0.px, 164.px)) {
                Text("Popups", tags = listOf("card-title"))
                Row(
                    id = "popup-anchor",
                    tags = listOf("popup-anchor"),
                    modifier = Modifier.input(hoverable = true, clickable = true)
                        .onPlaced { popupAnchorBounds = it }
                        .onEnter { popupTooltipVisible = true }
                        .onExit { popupTooltipVisible = false }
                        .onClick {
                            popupAnchorExpanded = !popupAnchorExpanded
                            it.consume()
                        }
                ) {
                    Text("Click: toggle popup · hover: tooltip", tags = listOf("popup-anchor-label"))
                }
                if (popupAnchorExpanded) {
                    Popup(
                        id = "popup-near-anchor",
                        anchorBounds = popupAnchorBounds,
                        alignment = UiPopupAlignment(offsetY = 6f),
                        tags = listOf("popup-panel"),
                        onDismiss = { popupAnchorExpanded = false },
                    ) {
                        Text("Node anchored popup", tags = listOf("popup-title"))
                        Text("Click the widget again or outside to close.", tags = listOf("popup-body"))
                    }
                }
                if (popupTooltipVisible) {
                    val pointer = LocalPointer.current
                    Popup(
                        id = "popup-near-cursor",
                        anchorBounds = UiRect(pointer.x, pointer.y, 0f, 0f),
                        alignment = UiPopupAlignment.Cursor,
                        layer = 100,
                        tags = listOf("popup-panel", "cursor-popup"),
                    ) {
                        Text("Tooltip follows cursor", tags = listOf("popup-title"))
                    }
                }
            }
        }
    }

    @Composable
    private fun textLab() = TextLab()

    @Composable
    private fun inlineFlowLab() {
        Column(
            tags = listOf("panel", "textlab-panel"),
            modifier = Modifier.scrollable()
        ) {
            Row(tags = listOf("textlab-controls")) {
                toggle("wrap", textLabWrap) { textLabWrap = !textLabWrap }
                toggle("effects", textLabEffects) { textLabEffects = !textLabEffects }
                pill("align: ${textLabAlign.name.lowercase()}") {
                    textLabAlign = UiTextAlign.entries[(textLabAlign.ordinal + 1) % UiTextAlign.entries.size]
                }
                Text("drag the handle to resize · width ${textLabWidth.toInt()}px", tags = listOf("textlab-hint"))
            }

            // Resizable stage: drag the right-edge handle to watch wrapping/overflow change.
            Row(tags = listOf("textlab-stage-row")) {
                Column(tags = listOf("textlab-card"), modifier = Modifier.size(textLabWidth.px, UiLength.Auto)) {
                    Text("Flow: wrapping, span sizes, groups, widgets, effects", tags = listOf("card-title"))
                    // Fill the card width so alignment has room to act; with wrap off, clip the
                    // single overflowing line to the card instead of letting it spill out.
                    var paraMod = Modifier.size(100.percent, UiLength.Auto).textAlign(textLabAlign)
                    if (!textLabWrap) paraMod = paraMod.textWrap(false).clip()
                    Text(tags = listOf("textlab-para"), modifier = paraMod) {
                        Span("Mixed ")
                        Span("sizes", tags = listOf("fx-big"))
                        Span(" share a line, plus a ")
                        Text(tags = listOf("group-slice")) { Span("sliced highlight that stays continuous across wraps") }
                        Span(", a ")
                        Text(tags = listOf("group-clone")) { Span("cloned group") }
                        Span(", an inline ")
                        Box(mode = UiBoxMode.STACK, tags = listOf("textlab-chip")) {
                            Text(
                                "chip",
                                tags = listOf("textlab-chip-label"),
                                modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER)
                            )
                        }
                        Span(" widget")
                        if (textLabEffects) {
                            Span(", and effects: ")
                            Span("bold ", tags = listOf("fx-bold"))
                            Span("italic ", tags = listOf("fx-italic"))
                            Span("underline ", tags = listOf("fx-underline"))
                            Span("strike ", tags = listOf("fx-strike"))
                            Span("heavy ", tags = listOf("fx-heavy"))
                            Span("oblique ", tags = listOf("fx-oblique"))
                            Span("ruled ", tags = listOf("fx-thick-rule"))
                            Span("wave ", tags = listOf("fx-wave"))
                            Span("shake ", tags = listOf("fx-shake"))
                            Span("rainbow", tags = listOf("fx-rainbow"))
                        }
                        Span(". Rendered in an ")
                        Span("MSDF font", tags = listOf("fx-msdf"))
                        Span(".\nA hard \\n break always wraps, even when text-wrap is off. ")
                        Caret(tags = listOf("textflow-caret"))
                    }
                }
                Box(
                    tags = listOf("textlab-handle"),
                    // draggable=true so the drag owns the pointer and the resize cursor stays put
                    // instead of flickering as the mouse leaves the thin handle mid-drag.
                    modifier = Modifier.input(hoverable = true, draggable = true)
                        .cursor(UiCursorShape.RESIZE_HORIZONTAL)
                        .onDrag { textLabWidth = (textLabWidth + it.deltaX).coerceIn(120f, 900f) },
                )
            }

            Text("Overflow: fixed height with vertical scroll", tags = listOf("card-title", "textlab-section"))
            Box(tags = listOf("textlab-scroll"), modifier = Modifier.scrollable(horizontal = false)) {
                Text(tags = listOf("textlab-para")) {
                    Span(
                        "This paragraph is intentionally long so it overflows the fixed-height box and must scroll. ".repeat(
                            6
                        )
                    )
                }
            }
        }
    }

    @Composable
    private fun pill(label: String, onClick: () -> Unit) {
        Row(
            tags = listOf("textlab-toggle"),
            modifier = Modifier.input(hoverable = true, clickable = true).onClick { onClick() },
        ) {
            Text(label, tags = listOf("textlab-toggle-label"))
        }
    }

    @Composable
    private fun toggle(label: String, checked: Boolean, onToggle: () -> Unit) {
        // A plain non-interactive indicator (not a Checkbox widget) so the whole row — icon
        // included — routes the click to onClick instead of the checkbox eating it.
        Row(
            tags = listOf("textlab-toggle"),
            modifier = Modifier.input(hoverable = true, clickable = true).onClick { onToggle() },
        ) {
            Box(
                tags = listOf("textlab-check"),
                modifier = Modifier.background(
                    if (checked) UiColor(0.36f, 0.78f, 0.5f, 1f) else UiColor(0.3f, 0.34f, 0.4f, 1f),
                ),
            )
            Text(label, tags = listOf("textlab-toggle-label"))
        }
    }

    @Composable
    private fun editorDemo() {
        HollowUiEditorDemo(
            keyLog = editorKeyLog,
            onKeyLog = { editorKeyLog = it },
        )
    }

    @Composable
    private fun layout() {
        Box(tags = listOf("free-stage"), modifier = Modifier.scrollable()) {
            repeat(12) { index ->
                val offset = freeNodeOffsets[index] ?: DemoOffset.Zero
                Column(
                    id = "free-node-$index",
                    tags = listOf("free-node"),
                    modifier = Modifier.position((40 + index * 96 + offset.x).px, (40 + index % 4 * 82 + offset.y).px)
                        .input(hoverable = true, draggable = true)
                        .onDrag {
                            val nodeKey = it.node.id
                            val index = nodeKey?.removePrefix("free-node-")?.toIntOrNull() ?: return@onDrag
                            val current = freeNodeOffsets[index] ?: DemoOffset.Zero
                            freeNodeOffsets[index] = DemoOffset(current.x + it.deltaX, current.y + it.deltaY)
                            it.consume()
                        }
                ) {
                    Text("Нода ${index + 1}", tags = listOf("free-label"))
                }
            }
            Column(
                id = "layout-glass",
                tags = listOf("layout-glass", "glass-card"),
                modifier = Modifier.position((140 + layoutGlassOffset.x).px, (260 + layoutGlassOffset.y).px)
                    .input(hoverable = true, draggable = true)
                    .onDrag {
                        layoutGlassOffset = DemoOffset(layoutGlassOffset.x + it.deltaX, layoutGlassOffset.y + it.deltaY)
                        it.consume()
                    }
            ) {
                Text("Стекляшка", tags = listOf("card-title"))
                Text("Ну и нафиг я это делал? Кому вообещ нужно размывать чате по центру...", tags = listOf("body"))
            }
        }
    }

    @Composable
    private fun docking() {
        DockSpace(
            state = dockingState,
            id = "demo-dock-space",
            modifier = Modifier.size(100.percent, 100.percent),
        ) { item ->
            Column(
                id = "dock-demo-${item.id}",
                tags = listOf("panel"),
                modifier = Modifier.padding(12.px)
                    .gap(8.px)
            ) {
                Text(item.title, tags = listOf("title"))
                when (item.id) {
                    "project" -> dockDemoRows("Scenes", "Scripts", "Assets", "Dialogues", "Cutscenes")
                    "editor" -> dockDemoRows("fun main() {", "    scene(\"intro\")", "    character(\"Ada\")", "}")
                    "preview" -> dockPreview()
                    "console" -> dockDemoRows(
                        "[info] Runtime started",
                        "[info] UI hot reload ready",
                        "[warn] Missing optional icon"
                    )

                    "inspector" -> dockDemoRows("Transform", "Position: 0, 0, 0", "Rotation: 0, 0, 0", "Scale: 1, 1, 1")
                    else -> Text("Empty panel", tags = listOf("body"))
                }
            }
        }
    }

    @Composable
    private fun dockDemoRows(vararg rows: String) {
        rows.forEach { row ->
            Row(tags = listOf("row")) {
                Text(row, tags = listOf("body"))
            }
        }
    }

    @Composable
    private fun dockPreview() {
        Box(
            modifier = Modifier.size(100.percent, 100.percent)
                .background(UiColor(0.08f, 0.1f, 0.13f, 1f))
                .border(1.px, UiColor(0.24f, 0.28f, 0.34f, 1f), 6f)
        ) {
            Text("Viewport", tags = listOf("body"), modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER))
        }
    }

    @Composable
    private fun effects() {
        Box(tags = listOf("effects-stage"), modifier = Modifier.scrollable()) {
            Column(tags = listOf("effect-card", "gradient-card"), modifier = Modifier.position(20.px, 18.px)) {
                Text("Градиент", tags = listOf("card-title"))
                Text("Ну типа карточка, но с градиентом.", tags = listOf("body"))
            }
            Column(
                tags = listOf("effect-card", "grayscale-card"),
                modifier = Modifier.position(220.px, 18.px).input(hoverable = true),
            ) {
                Text("ЧБ фильтр", tags = listOf("card-title"))
                Image("hollowengine:textures/gui/npc_menu/character.png", tags = listOf("preview-image"))
                Text("Работает даже на картинки")
            }
            var flipHovered by remember { mutableStateOf(false) }
            val frontRotation = if (flipHovered) 180f else 0f
            val backRotation = if (flipHovered) 0f else -180f
            Box(
                id = "flip-zone",
                tags = listOf("flip-zone"),
                modifier = Modifier.position(420.px, 18.px)
                    .input(hoverable = true)
                    .onEnter { flipHovered = true }
                    .onExit { flipHovered = false },
            ) {
                Column(
                    tags = listOf("effect-card", "flip-face", "flip-front"),
                    modifier = Modifier.position(0.px, 0.px)
                        .rotate(y = frontRotation, z = frontRotation / 4f)
                        .backfaceVisibility(UiBackfaceVisibility.HIDDEN),
                ) {
                    Text("Лицевая сторона", tags = listOf("card-title"))
                    Text("Положи сюда курсор", tags = listOf("body"))
                }
                Column(
                    tags = listOf("effect-card", "flip-face", "flip-back"),
                    modifier = Modifier.position(0.px, 0.px)
                        .rotate(y = backRotation, z = frontRotation / 4f)
                        .backfaceVisibility(UiBackfaceVisibility.HIDDEN)
                ) {
                    Text("Задняя сторона", tags = listOf("card-title"))
                    Text("А тут тоже чёта есть.", tags = listOf("body"))
                }
            }
            Column(
                tags = listOf("effect-card", "paper-card"),
                modifier = Modifier.position(20.px, 168.px).input(hoverable = true),
            ) {
                Text("Карточка в 3В", tags = listOf("card-title", "paper-title"))
                Text("3D на удивление можно делать всякие интересные штуки.", tags = listOf("body", "paper-body"))
            }
            Column(tags = listOf("effect-card", "glass-card"), modifier = Modifier.position(220.px, 168.px)) {
                Text("Размытие фона", tags = listOf("card-title"))
                Text("Только размывать-то нечего?", tags = listOf("body"))
            }
            Column(tags = listOf("effect-card", "css-lift-card"), modifier = Modifier.position(420.px, 168.px)) {
                Text("Карта Халвы", tags = listOf("card-title", "paper-title"))
                Text(
                    "За неё ничего нельзя купить... Но зато она прикольно леветирует",
                    tags = listOf("body", "paper-body")
                )
                Item("minecraft:diamond")
            }
            Column(
                tags = listOf("effect-card", "soft-focus-card"),
                modifier = Modifier.position(620.px, 168.px).input(hoverable = true),
            ) {
                Text("Карточка с размытием", tags = listOf("card-title", "soft-title"))
                Text("А теперь её видно нормально!", tags = listOf("body", "soft-body"))
            }
        }
    }

    @Composable
    private fun videoDemo() {
        Column(tags = listOf("panel"), modifier = Modifier.padding(12.px).gap(8.px)) {
            Text("Видео-плеер", tags = listOf("title"))
            Text(
                "Виджет Video поверх видео-аддона: ховер показывает контролы, попап громкости открывается наведением. Без аддона здесь будет заглушка.",
                tags = listOf("body"),
            )
            Video(
                source = "2026-06-21 20-01-15.mp4",
                id = "demo-video",
                autoPlay = false,
                modifier = Modifier.size(100.percent, UiLength.Auto).grow().borderRadius(6f),
            )
        }
    }
}

private data class DemoOffset(
    val x: Float,
    val y: Float,
) {
    companion object {
        val Zero = DemoOffset(0f, 0f)
    }
}
