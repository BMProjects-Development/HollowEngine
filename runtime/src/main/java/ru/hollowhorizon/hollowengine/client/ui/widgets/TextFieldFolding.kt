package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.common.scripting.ide.CodeFold

/** Folding owns presentation state only. Copy, save, undo and analysis always use the complete text. */
class TextFieldFolding {
    private var text = ""
    private var regions: List<CodeFold> = emptyList()
    private var sourceRegions: List<CodeFold> = emptyList()
    private var defaultsApplied = false
    private var collapsed by mutableStateOf<Set<Int>>(emptySet())
    private data class ProjectionKey(val text: String, val regions: List<CodeFold>, val collapsed: Set<Int>)
    private var cachedProjection: Pair<ProjectionKey, EditableFieldFoldProjection>? = null

    fun update(document: String, next: List<CodeFold>) {
        synchronizeText(document)
        if (next == sourceRegions) return
        regions = next.filter { it.start >= 0 && it.end > it.start && it.end <= document.length &&
            it.prefixLength >= 0 && it.prefixLength < it.end - it.start }
            .sortedWith(compareBy<CodeFold> { it.start }.thenByDescending { it.end })
        collapsed = collapsed.intersect(regions.mapTo(HashSet()) { it.start })
        if (!defaultsApplied && regions.any { it.collapsedByDefault }) {
            collapsed = collapsed + regions.filter { it.collapsedByDefault }.map { it.start }
            defaultsApplied = true
        }
        sourceRegions = next
    }

    fun isCollapsed(fold: CodeFold): Boolean = fold.start in collapsed

    fun toggle(fold: CodeFold) {
        if (fold !in regions) return
        collapsed = if (fold.start in collapsed) collapsed - fold.start else collapsed + fold.start
    }

    internal fun synchronizeText(document: String) {
        if (document == text) return
        val mapped = shiftCodeFoldsThroughEdit(text, document, regions)
        val mappedCollapsed = shiftCodeFoldsThroughEdit(text, document, regions.filter(::isCollapsed))
        regions = mapped
        collapsed = mappedCollapsed.mapTo(HashSet()) { it.start }
        text = document
    }

    /** Navigation into a hidden section opens every enclosing fold, including nested ones. */
    fun reveal(offset: Int) {
        collapsed = collapsed - regions.filter { fold ->
            offset > fold.start + fold.prefixLength && offset < fold.end
        }.map { it.start }.toSet()
    }

    internal fun projection(): EditableFieldFoldProjection {
        val key = ProjectionKey(text, regions, collapsed)
        cachedProjection?.takeIf { it.first == key }?.let { return it.second }
        val lines = editableFieldLines(text)
        val projected = lines.toMutableList()
        val owners = IntArray(lines.size) { it }
        val hidden = HashSet<Int>()
        val hints = ArrayList<UiInlayHint>()
        var previousEnd = -1
        regions.filter(::isCollapsed).forEach { fold ->
            val header = lines.lineIndex(fold.start)
            val start = fold.start + fold.prefixLength
            if (start < previousEnd || start >= fold.end) return@forEach
            val last = lines.lineIndex(fold.end - 1)
            val owner = owners[header]
            projected[owner] = EditableFieldLine(lines[owner].start, text.substring(lines[owner].start, lines[last].end))
            for (index in owner + 1..last) {
                hidden += index
                owners[index] = owner
            }
            hints += UiInlayHint(start, listOf(UiInlayContent.Label(fold.placeholder)), listOf("fold-placeholder"),
                UiInlayAction("fold:${fold.start}"), sourceLength = fold.end - start)
            previousEnd = fold.end
        }
        return EditableFieldFoldProjection(projected, hidden, hints).also { cachedProjection = key to it }
    }

    internal fun activate(action: UiInlayAction): Boolean {
        if (!action.id.startsWith("fold:")) return false
        val offset = action.id.removePrefix("fold:").toIntOrNull() ?: return false
        val fold = regions.firstOrNull { it.start == offset } ?: return false
        toggle(fold)
        return true
    }

    /** Ordinary editor movement treats a replacement as one indivisible symbol. */
    internal fun visibleOffset(offset: Int, previous: Int): Int {
        val fold = regions.firstOrNull { isCollapsed(it) && offset > it.start + it.prefixLength && offset < it.end }
            ?: return offset
        return if (offset >= previous) fold.end else fold.start + fold.prefixLength
    }
}

internal data class EditableFieldFoldProjection(
    val lines: List<EditableFieldLine>,
    val hiddenLines: Set<Int>,
    val placeholders: List<UiInlayHint>,
)

data class UiGutterMarker(
    val id: String,
    val offset: Int,
    val icon: String,
    val tooltip: String,
    val tags: List<String> = emptyList(),
    val onClick: (() -> Unit)? = null,
)

fun shiftCodeFoldsThroughEdit(original: String, edited: String, folds: List<CodeFold>): List<CodeFold> {
    if (original == edited) return folds
    val edit = uiTextEditSpan(original, edited)
    return folds.mapNotNull { fold ->
        when {
            fold.end <= edit.oldStart -> fold
            fold.start >= edit.oldEnd -> fold.copy(start = fold.start + edit.delta, end = fold.end + edit.delta)
            else -> null
        }
    }
}
