package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.IconHelper
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

internal const val SearchOverlayInputId = "ide-search-overlay-input"

/** Which half of the project a query is aimed at. */
internal enum class HollowIdeSearchScope(private val labelKey: String, private val tooltipKey: String) {
    FILES(SearchLang.FILES, SearchLang.FILES_HINT),
    TEXT(SearchLang.TEXT, SearchLang.TEXT_HINT);

    val label: String get() = labelKey.lang
    val tooltip: String get() = tooltipKey.lang
}

internal object SearchLang {
    private const val ROOT = "hollowengine.gui.ide.search."

    const val EMPTY = ROOT + "empty"
    const val FAILED = ROOT + "failed"
    const val FILES = ROOT + "files"
    const val FILES_HINT = ROOT + "files_hint"
    const val FOUND = ROOT + "found"
    const val QUERY_HINT = ROOT + "query_hint"
    const val SEARCHING = ROOT + "searching"
    const val TEXT = ROOT + "text"
    const val TEXT_HINT = ROOT + "text_hint"
}

/**
 * One hit: a file, or a line inside one. [offset] is where the caret lands when it is opened;
 * [nameMatch] and [detailMatch] are the stretches the query accounts for, so the row can point at
 * what was actually found instead of leaving the reader to spot it.
 */
internal data class HollowIdeSearchResult(
    val path: String,
    val detail: String,
    val offset: Int = 0,
    val nameMatch: IntRange? = null,
    val detailMatch: IntRange? = null,
    val icon: String = IconHelper.forPath(path).toString(),
) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * The search overlay: one query box that either matches file names or the text inside files, with
 * the scan running off the render thread and debounced, so typing in a project of any size stays
 * responsive.
 */
internal class HollowIdeSearchController(private val setStatus: (String) -> Unit) {
    var visible by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var scope by mutableStateOf(HollowIdeSearchScope.FILES)
        private set
    var selectedIndex by mutableStateOf(0)
        private set
    var searching by mutableStateOf(false)
        private set

    val results = mutableStateListOf<HollowIdeSearchResult>()

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun open(seed: String?) {
        visible = true
        if (!seed.isNullOrBlank() && '\n' !in seed) {
            query = seed
        }
        restart()
    }

    fun close() {
        visible = false
        job?.cancel()
        job = null
        searching = false
    }

    fun updateQuery(next: String) {
        query = next
        restart()
    }

    fun switchScope(next: HollowIdeSearchScope) {
        if (scope == next) return
        scope = next
        restart()
    }

    fun moveSelection(delta: Int) {
        if (results.isEmpty()) return
        selectedIndex = (selectedIndex + delta).coerceIn(0, results.lastIndex)
    }

    fun select(index: Int) {
        selectedIndex = index.coerceIn(0, (results.size - 1).coerceAtLeast(0))
    }

    fun selected(): HollowIdeSearchResult? = results.getOrNull(selectedIndex)

    private fun restart() {
        job?.cancel()
        selectedIndex = 0
        val current = query.trim()
        if (current.isEmpty()) {
            results.clear()
            searching = false
            return
        }
        searching = true
        val activeScope = scope
        job = coroutineScope.launch {
            delay(SearchDebounceMillis.milliseconds)
            ensureActive()
            val found = runCatching {
                when (activeScope) {
                    HollowIdeSearchScope.FILES -> searchFileNames(current)
                    HollowIdeSearchScope.TEXT -> searchFileContents(current)
                }
            }.getOrElse { failure ->
                setStatus(SearchLang.FAILED.lang(failure.message ?: failure::class.simpleName.orEmpty()))
                emptyList()
            }
            ensureActive()
            Minecraft.getInstance().execute {
                if (!visible || query.trim() != current || scope != activeScope) return@execute
                results.clear()
                results += found
                selectedIndex = 0
                searching = false
            }
        }
    }
}

private const val SearchDebounceMillis = 140L
private const val SearchMaxResults = 200
private const val SearchMaxFileBytes = 2 * 1024 * 1024L

private fun projectFiles(): Sequence<File> = DirectoryManager.HOLLOW_ENGINE.toFile().walkTopDown()
    .onEnter { directory -> directory.name != ".git" && directory.name != "cache" }
    .filter { it.isFile }

private fun File.readablePath(): String =
    DirectoryManager.HOLLOW_ENGINE.relativize(toPath()).toString().replace(File.separatorChar, '/')

private fun searchFileNames(query: String): List<HollowIdeSearchResult> {
    val needle = query.replace('\\', '/')
    return projectFiles()
        .map { it.readablePath() }
        .filter { it.contains(needle, ignoreCase = true) }
        .sortedWith(
            compareByDescending<String> { it.substringAfterLast('/').contains(needle, ignoreCase = true) }
                .thenBy { it.length }
                .thenBy { it },
        )
        .take(SearchMaxResults)
        .map { path ->
            val folder = path.substringBeforeLast('/', "")
            val name = path.substringAfterLast('/')
            HollowIdeSearchResult(
                path = path,
                detail = folder,
                nameMatch = name.matchRangeOf(needle),
                detailMatch = folder.matchRangeOf(needle),
            )
        }
        .toList()
}

/** Where [needle] sits in the receiver, ignoring case; null when it is not there at all. */
private fun String.matchRangeOf(needle: String): IntRange? {
    if (needle.isEmpty()) return null
    val start = indexOf(needle, ignoreCase = true)
    return if (start < 0) null else start until start + needle.length
}

private fun searchFileContents(query: String): List<HollowIdeSearchResult> {
    val results = ArrayList<HollowIdeSearchResult>()
    for (file in projectFiles()) {
        if (results.size >= SearchMaxResults) break
        if (file.length() > SearchMaxFileBytes) continue
        val text = runCatching { file.readText() }.getOrNull() ?: continue
        if (text.looksBinary()) continue
        var index = text.indexOf(query, ignoreCase = true)
        val path = file.readablePath()
        while (index >= 0 && results.size < SearchMaxResults) {
            val lineStart = text.lastIndexOf('\n', (index - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val lineEnd = text.indexOf('\n', index).let { if (it < 0) text.length else it }
            val rawLine = text.substring(lineStart, lineEnd)
            val trimmed = rawLine.trimStart()
            val line = trimmed.trimEnd().take(SearchPreviewLength)
            val lineNumber = text.take(lineStart).count { it == '\n' } + 1
            val prefix = "$lineNumber: "
            val inPreview = index - lineStart - (rawLine.length - trimmed.length) + prefix.length
            val detail = prefix + line
            results += HollowIdeSearchResult(
                path = path,
                detail = detail,
                offset = index,
                detailMatch = (inPreview until inPreview + query.length)
                    .takeIf { it.first >= prefix.length && it.last < detail.length },
            )
            index = text.indexOf(query, index + query.length.coerceAtLeast(1), ignoreCase = true)
        }
    }
    return results
}

private const val SearchPreviewLength = 120

/** A cheap binary sniff: a NUL byte in the first chunk means this is not something to grep. */
private fun String.looksBinary(): Boolean = take(4096).any { it.code == 0 }

@Composable
internal fun HollowIdeSearchDialog(
    controller: HollowIdeSearchController,
    onOpen: (HollowIdeSearchResult) -> Unit,
) {
    if (!controller.visible) return
    val screenWidth = HollowIdeScale.scaledWidth()
    val screenHeight = HollowIdeScale.scaledHeight()
    val width = SearchDialogWidth.coerceAtMost(screenWidth - 32f).coerceAtLeast(200f)

    Popup(
        anchorBounds = UiRect(((screenWidth - width) / 2f).coerceAtLeast(0f), screenHeight * 0.12f, 0f, 0f),
        alignment = UiPopupAlignment.Cursor,
        id = "ide-search-overlay",
        tags = listOf("dropdown-popup", "ide-search-overlay"),
        modifier = Modifier.size(width.px, UiLength.Fit),
        modal = true,
        onDismiss = controller::close,
    ) {
        Row(tags = listOf("ide-search-scopes")) {
            HollowIdeSearchScope.entries.forEach { scope ->
                key(scope) {
                    Box(
                        tags = listOf("ide-search-scope", if (controller.scope == scope) "on" else "off"),
                        modifier = Modifier.input(clickable = true, hoverable = true)
                            .cursor(UiCursorShape.HAND)
                            .onClick { event ->
                                controller.switchScope(scope)
                                event.consume()
                            }
                            .tooltipOnHover(scope.tooltip),
                    ) {
                        Text(scope.label, modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER).textWrap(false))
                    }
                }
            }
            Box(modifier = Modifier.size(0.px, 1.px).grow(1f))
            Text(
                when {
                    controller.searching -> SearchLang.SEARCHING.lang
                    controller.query.isBlank() -> SearchLang.EMPTY.lang
                    else -> SearchLang.FOUND.lang(controller.results.size)
                },
                tags = listOf("ide-search-status"),
            )
        }
        Row(tags = listOf("ide-search-query-row"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
            Image(SearchIcon, tags = listOf("ide-search-icon"))
            TextField(
                value = controller.query,
                placeholder = SearchLang.QUERY_HINT.lang,
                onChange = controller::updateQuery,
                id = SearchOverlayInputId,
                tags = listOf("ide-search-input"),
                modifier = Modifier.grow(1f).onKeyInput { input ->
                    if (handleHollowIdeSearchKey(controller, input.key, input.modifiers, onOpen)) input.consume()
                },
            )
        }
        LaunchedEffect(Unit) {
            Minecraft.getInstance().execute { HollowIdeOverlay.focusSurface(SearchOverlayInputId) }
        }
        if (controller.results.isNotEmpty()) {
            Column(tags = listOf("ide-search-results"), modifier = Modifier.scrollable()) {
                controller.results.forEachIndexed { index, result ->
                    key(index) {
                        Row(
                            tags = listOf(
                                "ide-search-result",
                                if (index == controller.selectedIndex) "selected" else "idle",
                            ),
                            modifier = Modifier.input(clickable = true, hoverable = true)
                                .cursor(UiCursorShape.HAND)
                                .alignItems(vertical = UiAlign.CENTER)
                                .onClick { event ->
                                    controller.select(index)
                                    onOpen(result)
                                    event.consume()
                                },
                        ) {
                            Image(result.icon, tags = listOf("ide-search-result-icon"))
                            MatchedText(result.name, result.nameMatch, "ide-search-result-name")
                            MatchedText(result.detail, result.detailMatch, "ide-search-result-detail")
                        }
                    }
                }
            }
        }
    }
}

/** A result label with the stretch the query accounts for called out. */
@Composable
private fun MatchedText(text: String, match: IntRange?, tag: String) {
    val range = match?.let { it.first.coerceIn(0, text.length)..(it.last + 1).coerceIn(0, text.length) }
        ?.takeIf { it.last > it.first }
    if (range == null) {
        Text(text, tags = listOf(tag))
        return
    }
    Text(tags = listOf(tag), modifier = Modifier.textWrap(false)) {
        if (range.first > 0) Span(text.substring(0, range.first))
        Span(text.substring(range.first, range.last), tags = listOf("ide-search-result-match"))
        if (range.last < text.length) Span(text.substring(range.last))
    }
}

/** Overlay navigation: arrows walk the list, Enter opens, Tab flips the scope, Escape closes. */
internal fun handleHollowIdeSearchKey(
    controller: HollowIdeSearchController,
    key: Int,
    modifiers: Int,
    onOpen: (HollowIdeSearchResult) -> Unit,
): Boolean {
    if (!controller.visible) return false
    return when (key) {
        GLFW.GLFW_KEY_ESCAPE -> {
            controller.close()
            true
        }

        GLFW.GLFW_KEY_UP -> {
            controller.moveSelection(-1)
            true
        }

        GLFW.GLFW_KEY_DOWN -> {
            controller.moveSelection(1)
            true
        }

        GLFW.GLFW_KEY_TAB -> {
            val entries = HollowIdeSearchScope.entries
            val delta = if (modifiers and GLFW.GLFW_MOD_SHIFT != 0) -1 else 1
            val next = (controller.scope.ordinal + delta + entries.size) % entries.size
            controller.switchScope(entries[next])
            true
        }

        GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
            controller.selected()?.let(onOpen)
            true
        }

        else -> false
    }
}

private const val SearchDialogWidth = 560f
