package ru.hollowhorizon.hollowengine.client.ui.ide.panels

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.spi.StandardLevel
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.docking.DockTags
import ru.hollowhorizon.hollowengine.client.ui.docking.LocalDockPanelTitle
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeConsoleFontSize
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeProblems
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeProblemsList
import ru.hollowhorizon.hollowengine.client.ui.scroll.rememberScrollState
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.logging.HollowLogEntry
import ru.hollowhorizon.hollowengine.common.logging.HollowLogStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val LANG = "hollowengine.gui.console"

/**
 * The bottom tool window: the log with its command line, and the problems of the file being edited.
 */
@Composable
internal fun HollowIdeConsolePanel(console: HollowIdeConsole, problems: HollowIdeProblems) {
    Column(
        tags = listOf("ide-panel", "console-panel"),
        modifier = Modifier.size(100.percent, 100.percent),
    ) {
        ConsoleHeader(console, problems)
        when (console.tab) {
            ConsoleTab.LOGS -> {
                ConsoleLog(console)
                ConsoleInput(console)
            }

            ConsoleTab.PROBLEMS -> HollowIdeProblemsList(problems)
        }
    }
}

@Composable
private fun ConsoleHeader(console: HollowIdeConsole, problems: HollowIdeProblems) {
    val parked = LocalDockPanelTitle.current
    Row(id = parked?.headerId, tags = listOf("tool-window-header"), modifier = parked?.dragHandle ?: Modifier) {
        parked?.let { panel ->
            panel.icon?.let { icon -> Image(icon, tags = listOf(DockTags.PinnedHeaderIcon)) }
            Text(panel.title, tags = listOf(DockTags.PinnedHeaderLabel, "tool-window-title"))
        }
        ConsoleTab.entries.forEach { tab ->
            ConsoleTabButton(console, tab, if (tab == ConsoleTab.PROBLEMS) problems else null)
        }
        Box(modifier = Modifier.size(0.px, 1.px).grow(1f))
        if (console.tab == ConsoleTab.LOGS) ConsoleToolbar(console)
    }
}

@Composable
private fun ConsoleTabButton(console: HollowIdeConsole, tab: ConsoleTab, problems: HollowIdeProblems?) {
    Row(
        id = "console-tab-${tab.name.lowercase()}",
        tags = listOfNotNull("tool-window-tab", "selected".takeIf { console.tab == tab }),
        modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND).onClick { event ->
                console.tab = tab
                event.consume()
            },
    ) {
        Text(tab.langKey.lang, tags = listOf("tool-window-tab-label"))
        val count = problems?.diagnostics?.size ?: 0
        if (problems != null && count > 0) {
            Text(count.toString(), tags = listOf("tool-window-tab-count", problems.severityTag))
        }
    }
}

/** Level, filter and auto-scroll of the log. */
@Composable
private fun ConsoleToolbar(console: HollowIdeConsole) {
    var levelMenuExpanded by remember { mutableStateOf(false) }
    Row(tags = listOf("console-toolbar")) {
        UiDropdown(
            id = "console-level",
            label = console.minimumLevel.name,
            expanded = levelMenuExpanded,
            onExpandedChange = { levelMenuExpanded = it },
            items = ConsoleLevels.map { level ->
                UiDropdownItem(level.name, checked = level == console.minimumLevel, mark = UiDropdownMark.RADIO) {
                    console.minimumLevel = level
                }
            },
            tags = listOf("console-level-dropdown"),
        )
        TextField(
            value = console.filterText,
            placeholder = "$LANG.filter_hint".lang,
            onChange = { console.filterText = it },
            textShadow = null,
            id = "console-filter",
            tags = listOf("console-filter"),
            modifier = Modifier.size(FilterWidth.px, 18.px),
        )
        Checkbox(
            checked = console.autoScroll,
            variant = UiCheckboxVariant.SWITCH,
            onCheckedChange = { console.autoScroll = it },
            id = "console-auto-scroll",
            tags = listOf("console-auto-scroll"),
            modifier = Modifier.tooltipOnHover("$LANG.auto_scroll".lang),
        )
    }
}

@Composable
private fun ConsoleLog(console: HollowIdeConsole) {
    var snapshot by remember { mutableStateOf(HollowLogStore.snapshot()) }
    val fontSize = HollowIdeConsoleFontSize.size

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            HollowLogStore.snapshotAfter(snapshot.revision)?.let { snapshot = it }
        }
    }

    val filter = remember(console.filterText) { ConsoleLogFilter.compile(console.filterText) }
    val minimumLevel = console.minimumLevel
    val visibleEntries = remember(snapshot, minimumLevel, filter) {
        snapshot.entries.filter { entry -> entry.level.ordinal <= minimumLevel.ordinal && filter.matches(entry) }
    }

    val document = remember { ConsoleLogDocument() }
    val logState = remember {
        TextFieldState(
            multiline = true, readOnly = true, autoPairs = false, indentSize = null, wrap = false, textShadow = null,
        )
    }
    val revision = remember(visibleEntries) {
        document.sync(visibleEntries, ConsoleLogDocument.Key(minimumLevel, console.filterText))
        document.revision
    }
    logState.fontSize = fontSize
    logState.fontFamily = LogFontFamily
    val highlights = document.highlights
    val highlighter = remember(revision) { UiSyntaxHighlighter { highlights } }
    val logScroll = rememberScrollState()
    SideEffect {
        if (logState.text != document.text) logState.setText(document.text, moveCaretToEnd = false)
    }
    LaunchedEffect(revision, console.autoScroll) {
        if (!console.autoScroll) return@LaunchedEffect
        // The layout of the new lines is only known on the next frame, so ask twice.
        repeat(AutoScrollLayoutFrames) {
            withFrameNanos { }
            logScroll.scrollTo(y = Float.MAX_VALUE)
        }
    }

    if (document.text.isEmpty()) {
        Box(tags = listOf("console-empty"), modifier = Modifier.size(100.percent, 0.px).grow(1f)) {
            Text("$LANG.empty".lang)
        }
        return
    }
    EditableTextField(
        state = logState,
        id = "console-log",
        tags = listOf("console-log"),
        scrollState = logScroll,
        syntaxHighlighter = highlighter,
        modifier = Modifier.size(100.percent, 0.px).grow(1f).onScroll { event ->
            if (event.isCtrlDown()) {
                HollowIdeConsoleFontSize.zoom(event.rawScrollY)
                event.consume()
            } else if (event.rawScrollY > 0f) {
                console.autoScroll = false
            }
        },
    )
}

@Composable
private fun ConsoleInput(console: HollowIdeConsole) {
    var modeMenuExpanded by remember { mutableStateOf(false) }
    val kotlinMode = console.mode == ConsoleInputMode.KOTLIN
    val multiline = kotlinMode && console.expanded
    val connected = kotlinMode || Minecraft.getInstance().connection != null
    val kotlinInlays = remember(console) {
        UiInlayHintsProvider { text -> console.kotlin.inlayHints(text) }
    }

    Row(
        tags = listOfNotNull("console-command-row", "expanded".takeIf { multiline }),
        modifier = Modifier.alignItems(vertical = if (multiline) UiAlign.START else UiAlign.CENTER),
    ) {
        UiDropdown(
            id = "console-mode",
            label = console.mode.langKey.lang,
            expanded = modeMenuExpanded,
            onExpandedChange = { modeMenuExpanded = it },
            items = ConsoleInputMode.entries.map { mode ->
                UiDropdownItem(mode.langKey.lang, checked = mode == console.mode, mark = UiDropdownMark.RADIO) {
                    console.mode = mode
                }
            },
            tags = listOf("console-mode-dropdown"),
        )
        TextField(
            value = console.input,
            mode = if (multiline) UiTextFieldMode.MULTI_LINE else UiTextFieldMode.SINGLE_LINE,
            onChange = { console.input = it },
            syntaxHighlighter = if (kotlinMode) console.kotlin.highlighter else console.commands.highlighter,
            completionContributor = if (kotlinMode) console.kotlin.completions else console.commands.contributor,
            signatureHelpProvider = if (kotlinMode) console.kotlin.signatures else null,
            hoverInfoProvider = if (kotlinMode) console.kotlin.hover else null,
            inlayHintsProvider = if (kotlinMode) kotlinInlays else null,
            inlayRevision = console.assistRevision,
            completionRevision = console.assistRevision,
            diagnostics = console.diagnostics(),
            indentSize = if (kotlinMode) 4 else null,
            autoPairs = kotlinMode,
            wrap = false,
            fontFamily = LogFontFamily,
            textShadow = null,
            placeholder = when {
                kotlinMode -> "$LANG.kotlin_hint".lang
                connected -> "$LANG.command_hint".lang
                else -> "$LANG.no_world".lang
            },
            id = "console-command",
            tags = listOf("console-command"),
            modifier = Modifier.size(0.px, (if (multiline) ExpandedInputHeight else InputHeight).px).grow(1f)
                .onKeyInput { input ->
                    if (input.isEnter() && input.control && !input.repeat) {
                        console.run()
                        input.consume()
                    }
                }.onKeyInput(TextFieldDefaultKeyPriority - 1) { input ->
                    if (multiline || input.repeat && input.isEnter()) return@onKeyInput
                    val handled = when (input.key) {
                        GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> true.also { console.run() }
                        GLFW.GLFW_KEY_UP -> console.browseHistory(older = true)
                        GLFW.GLFW_KEY_DOWN -> console.browseHistory(older = false)
                        else -> false
                    }
                    if (handled) input.consume()
                },
        )
        if (kotlinMode) {
            ConsoleIconButton(
                id = "console-expand",
                icon = if (console.expanded) MinimizeIcon else MaximizeIcon,
                tooltip = "$LANG.${if (console.expanded) "collapse" else "expand"}".lang,
            ) { console.expanded = !console.expanded }
            ConsoleIconButton(
                id = "console-stop",
                icon = StopIcon,
                tooltip = "$LANG.stop".lang,
                enabled = console.canStop,
                onClick = console::stopSnippets,
            )
        }
        ConsoleIconButton(
            id = "console-run",
            icon = RunIcon,
            tooltip = "$LANG.${if (kotlinMode) "run" else "execute"}".lang,
            enabled = console.canRun,
            onClick = console::run,
        )
    }
}

@Composable
private fun ConsoleIconButton(
    id: String,
    icon: String,
    tooltip: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = IconButton(icon, tooltip, id = id, enabled = enabled, tags = listOf("console-button"), onClick = onClick)

private fun UiKeyInput.isEnter(): Boolean = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER

/**
 * The log as one document plus the colors of its parts. Entries keep arriving one at a time, so the
 * common case only appends to what is already built; anything else (a new filter, entries dropped from
 * the buffer) rebuilds it.
 */
private class ConsoleLogDocument {
    private val builder = StringBuilder()
    private val spans = ArrayList<UiTextHighlight>()
    private var key: Key? = null
    private var firstId = NoEntry
    private var lastId = NoEntry

    var text: String = ""
        private set
    var highlights: List<UiTextHighlight> = emptyList()
        private set

    /** Bumped whenever [highlights] changes, so the field knows to highlight again. */
    var revision: Long = 0L
        private set

    fun sync(entries: List<HollowLogEntry>, key: Key) {
        val first = entries.firstOrNull()?.id ?: NoEntry
        val last = entries.lastOrNull()?.id ?: NoEntry
        if (this.key == key && firstId == first && lastId == last) return

        val appendable = this.key == key && firstId == first && last > lastId
        val added = if (appendable) entries.filter { it.id > lastId } else entries
        if (!appendable) {
            builder.setLength(0)
            spans.clear()
        }
        added.forEach(::append)
        this.key = key
        firstId = first
        lastId = last
        text = builder.toString()
        highlights = spans.toList()
        revision++
    }

    private fun append(entry: HollowLogEntry) {
        if (builder.isNotEmpty()) builder.append('\n')
        val levelColors = ConsoleLevelColors.getValue(entry.level)
        span(LogTimeFormatter.format(Instant.ofEpochMilli(entry.timeMillis)), TimeStyle)
        builder.append(' ')
        span(entry.level.name.padEnd(LevelLabelWidth), levelColors.level)
        builder.append(' ')
        entry.loggerName?.let { logger ->
            span(logger.substringAfterLast('.'), LoggerStyle)
            builder.append(' ')
        }
        span(entry.message.replace("\r\n", "\n").trimEnd(), levelColors.message)
    }

    private fun span(value: String, style: UiInlineStyle) {
        if (value.isEmpty()) return
        val start = builder.length
        builder.append(value)
        spans += UiTextHighlight(start, builder.length, style)
    }

    /** What the document was built from; a change to either of these means it has to be built again. */
    data class Key(val level: StandardLevel, val filter: String)

    private companion object {
        const val NoEntry = -1L
    }
}

private class ConsoleLogFilter private constructor(private val regex: Regex?) {
    fun matches(entry: HollowLogEntry): Boolean {
        val matcher = regex ?: return true
        return matcher.containsMatchIn(entry.message) || entry.loggerName?.let(matcher::containsMatchIn) == true
    }

    companion object {
        fun compile(value: String): ConsoleLogFilter {
            val query = value.trim()
            if (query.isEmpty()) return ConsoleLogFilter(null)
            val regex = runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrElse {
                    Regex(
                        Regex.escape(query),
                        RegexOption.IGNORE_CASE
                    )
                }
            return ConsoleLogFilter(regex)
        }
    }
}

private class LevelColors(level: UiColor, message: UiColor) {
    val level: UiInlineStyle = UiInlineStyle().withColor(level)
    val message: UiInlineStyle = UiInlineStyle().withColor(message)
}

private val ConsoleLevels = listOf(
    StandardLevel.FATAL,
    StandardLevel.ERROR,
    StandardLevel.WARN,
    StandardLevel.INFO,
    StandardLevel.DEBUG,
    StandardLevel.TRACE,
)

private val MessageColor = UiColor.fromArgb(0xFFD7DEEA.toInt())
private val QuietMessageColor = UiColor.fromArgb(0xFFA9B3C4.toInt())
private val TimeStyle = UiInlineStyle().withColor(UiColor.fromArgb(0xFF4F5968.toInt()))
private val LoggerStyle = UiInlineStyle().withColor(UiColor.fromArgb(0xFF7A8698.toInt()))
private val ErrorColors = LevelColors(UiColor.fromArgb(0xFFF07878.toInt()), UiColor.fromArgb(0xFFF29A9A.toInt()))
private val ConsoleLevelColors = mapOf(
    StandardLevel.OFF to LevelColors(MessageColor, MessageColor),
    StandardLevel.FATAL to ErrorColors,
    StandardLevel.ERROR to ErrorColors,
    StandardLevel.WARN to LevelColors(UiColor.fromArgb(0xFFE6B35A.toInt()), UiColor.fromArgb(0xFFE8CA92.toInt())),
    StandardLevel.INFO to LevelColors(UiColor.fromArgb(0xFF7CC49A.toInt()), MessageColor),
    StandardLevel.DEBUG to LevelColors(UiColor.fromArgb(0xFF6E8FBF.toInt()), QuietMessageColor),
    StandardLevel.TRACE to LevelColors(UiColor.fromArgb(0xFF5F6A7B.toInt()), UiColor.fromArgb(0xFF7A8698.toInt())),
    StandardLevel.ALL to LevelColors(MessageColor, MessageColor),
)

private val LogTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
private const val LogFontFamily = UiCodeFontFamily
private const val LevelLabelWidth = 5
private const val FilterWidth = 140f
private const val AutoScrollLayoutFrames = 2
private const val InputHeight = 22f
private const val ExpandedInputHeight = 120f

private const val RunIcon = "hollowengine:textures/gui/icons/play.svg"
private const val StopIcon = "hollowengine:textures/gui/icons/stop.svg"
private const val MaximizeIcon = "hollowengine:textures/gui/icons/maximize.svg"
private const val MinimizeIcon = "hollowengine:textures/gui/icons/minimize.svg"
