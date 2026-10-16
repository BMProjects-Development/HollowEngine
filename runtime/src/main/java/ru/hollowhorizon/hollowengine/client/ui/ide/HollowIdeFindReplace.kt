package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.Button
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.generated.Assets

/**
 * Find/replace for one open file. Deliberately owned by the editor rather than by whichever text
 * field happens to have focus: Ctrl+F acts on the file being edited.
 */
internal class HollowIdeFindState(val fileId: String) {
    var visible by mutableStateOf(false)
        private set
    var replaceVisible by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var replacement by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var regex by mutableStateOf(false)

    /** Index into the match list; the bar keeps it in range as the text changes underneath. */
    var currentIndex by mutableStateOf(0)

    /** Bumped by every [open]; the bar keys its "focus the query field" effect on it. */
    var openRevision by mutableStateOf(0)
        private set

    val findInputId: String get() = "ide-find-input-$fileId"
    val replaceInputId: String get() = "ide-replace-input-$fileId"

    private var cacheKey: List<Any> = emptyList()
    private var cachedMatches: List<IntRange> = emptyList()

    fun open(replace: Boolean, seed: String?) {
        visible = true
        replaceVisible = replace
        if (!seed.isNullOrEmpty() && '\n' !in seed) {
            query = seed
            currentIndex = 0
        }
        openRevision++
    }

    fun close() {
        visible = false
        replaceVisible = false
    }

    /**
     * Matches in [text], in document order. Memoized on the query and the toggles, so the editor can
     * ask on every frame without re-scanning a file that has not changed.
     */
    fun matches(text: String): List<IntRange> {
        if (!visible) return emptyList()
        val key = listOf(text.length, text.hashCode(), query, matchCase, wholeWord, regex)
        if (key != cacheKey) {
            cachedMatches = compiledPattern()
                ?.findAll(text)
                ?.mapNotNull { match -> match.range.takeIf { match.value.isNotEmpty() } }
                ?.toList()
                .orEmpty()
            cacheKey = key
        }
        return cachedMatches
    }

    /** Index of the first match at or after [offset], wrapping to the top when there is none. */
    fun indexFrom(matches: List<IntRange>, offset: Int): Int =
        matches.indexOfFirst { it.first >= offset }.takeIf { it >= 0 } ?: 0
}

/** The query as a regex, honoring the case/word/regex toggles; null when it cannot be searched. */
internal fun HollowIdeFindState.compiledPattern(): Regex? {
    if (query.isEmpty()) return null
    val body = if (regex) query else Regex.escape(query)
    val pattern = if (wholeWord) "(?<![\\p{L}\\p{N}_])(?:$body)(?![\\p{L}\\p{N}_])" else body
    val options = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
    return runCatching { Regex(pattern, options) }.getOrNull()
}

/** The replacement for [match], expanding `$1`-style group references only while in regex mode. */
internal fun HollowIdeFindState.expandReplacement(text: String, match: IntRange): String {
    if (!regex) return replacement
    val found = compiledPattern()?.find(text, match.first)?.takeIf { it.range == match } ?: return replacement
    return replacement.expandGroups(found)
}

private fun String.expandGroups(match: MatchResult): String {
    val out = StringBuilder(length)
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char == '\\' && index + 1 < length) {
            out.append(this[index + 1])
            index += 2
            continue
        }
        if (char == '$' && index + 1 < length && this[index + 1].isDigit()) {
            var end = index + 1
            while (end < length && this[end].isDigit()) end++
            val group = substring(index + 1, end).toInt()
            out.append(match.groupValues.getOrElse(group) { "" })
            index = end
            continue
        }
        out.append(char)
        index++
    }
    return out.toString()
}

internal class HollowIdeFindActions(
    val onNavigate: (Int) -> Unit,
    val onReplaceCurrent: () -> Unit,
    val onReplaceAll: () -> Unit,
    val onClose: () -> Unit,
)

@Composable
internal fun HollowIdeFindBar(
    state: HollowIdeFindState,
    matchCount: Int,
    actions: HollowIdeFindActions,
) {
    if (!state.visible) return
    val invalidRegex = state.regex && state.query.isNotEmpty() && state.compiledPattern() == null
    val keys: Modifier = Modifier.onKeyInput { input ->
        val handled = when (input.key) {
            GLFW.GLFW_KEY_ESCAPE -> {
                actions.onClose()
                true
            }

            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (input.control && state.replaceVisible) actions.onReplaceAll()
                else actions.onNavigate(if (input.shift) -1 else 1)
                true
            }

            else -> false
        }
        if (handled) input.consume()
    }

    LaunchedEffect(state.openRevision) {
        Minecraft.getInstance().execute { HollowIdeOverlay.focusSurface(state.findInputId) }
    }
    Column(
        tags = listOf("ide-find-bar"),
        modifier = Modifier.size(100.percent, UiLength.Fit),
    ) {
        Row(tags = listOf("ide-find-row"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
            Image(SearchIcon, tags = listOf("ide-find-icon"))
            TextField(
                value = state.query,
                placeholder = FindLang.QUERY_HINT.lang,
                onChange = {
                    state.query = it
                    state.currentIndex = 0
                },
                id = state.findInputId,
                tags = listOf("ide-find-input"),
                modifier = Modifier.grow(1f).then(keys),
            )
            FindToggle("Cc", FindLang.MATCH_CASE.lang, state.matchCase) { state.matchCase = it }
            FindToggle("W", FindLang.WHOLE_WORDS.lang, state.wholeWord) { state.wholeWord = it }
            FindToggle(".*", FindLang.REGEX.lang, state.regex) { state.regex = it }
            Text(
                findStatusText(state, matchCount, invalidRegex),
                tags = listOf("ide-find-status", if (invalidRegex || matchCount == 0) "empty" else "found"),
            )
            FindButton(ArrowIcon, FindLang.PREVIOUS.lang) { actions.onNavigate(-1) }
            FindButton(ArrowIcon, FindLang.NEXT.lang, flip = true) { actions.onNavigate(1) }
            FindButton(CrossIcon, FindLang.CLOSE.lang, action = actions.onClose)
        }
        if (state.replaceVisible) {
            Row(tags = listOf("ide-find-row"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
                Box(tags = listOf("ide-find-icon"))
                TextField(
                    value = state.replacement,
                    placeholder = FindLang.REPLACE_QUERY_HINT.lang,
                    onChange = { state.replacement = it },
                    id = state.replaceInputId,
                    tags = listOf("ide-find-input"),
                    modifier = Modifier.grow(1f).then(keys),
                )
                FindTextButton(FindLang.REPLACE.lang, FindLang.REPLACE_HINT.lang, actions.onReplaceCurrent)
                FindTextButton(FindLang.REPLACE_ALL.lang, FindLang.REPLACE_ALL_HINT.lang, actions.onReplaceAll)
            }
        }
    }
}

internal object FindLang {
    private const val ROOT = "hollowengine.gui.ide.find."

    const val BAD_PATTERN = ROOT + "bad_pattern"
    const val CLOSE = ROOT + "close"
    const val MATCH_CASE = ROOT + "match_case"
    const val NEXT = ROOT + "next"
    const val NO_RESULTS = ROOT + "no_results"
    const val NO_RESULTS_FOR = ROOT + "no_results_for"
    const val POSITION = ROOT + "position"
    const val PREVIOUS = ROOT + "previous"
    const val QUERY_HINT = ROOT + "query_hint"
    const val REGEX = ROOT + "regex"
    const val REPLACE = ROOT + "replace"
    const val REPLACE_ALL = ROOT + "replace_all"
    const val REPLACE_ALL_HINT = ROOT + "replace_all_hint"
    const val REPLACE_HINT = ROOT + "replace_hint"
    const val REPLACE_QUERY_HINT = ROOT + "replace_query_hint"
    const val REPLACED = ROOT + "replaced"
    const val WHOLE_WORDS = ROOT + "whole_words"
}

private fun findStatusText(state: HollowIdeFindState, matchCount: Int, invalidRegex: Boolean): String = when {
    invalidRegex -> FindLang.BAD_PATTERN.lang
    state.query.isEmpty() -> ""
    matchCount == 0 -> FindLang.NO_RESULTS.lang
    else -> FindLang.POSITION.lang(state.currentIndex.coerceIn(0, matchCount - 1) + 1, matchCount)
}

@Composable
private fun FindToggle(label: String, tooltip: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Button(label, active = checked, tooltip = tooltip, tags = listOf("ide-find-toggle")) { onChange(!checked) }

@Composable
private fun FindButton(icon: String, tooltip: String, flip: Boolean = false, action: () -> Unit) = IconButton(
    icon,
    tooltip,
    tags = listOf("ide-find-button"),
    modifier = if (flip) Modifier.rotate(z = 180f) else Modifier,
    onClick = action,
)

@Composable
private fun FindTextButton(label: String, tooltip: String, action: () -> Unit) =
    Button(label, tooltip = tooltip, tags = listOf("ide-find-text-button"), onClick = action)

private val ArrowIcon = Assets.Hollowengine.Textures.Gui.Icons.ARROW.toString()
private val CrossIcon = Assets.Hollowengine.Textures.Gui.Icons.CROSS.toString()
