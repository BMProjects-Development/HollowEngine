package ru.hollowhorizon.hollowengine.client.ui.ide.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.editor.EditorMode
import ru.hollowhorizon.hollowengine.client.editor.WorldHistory
import ru.hollowhorizon.hollowengine.client.history.UndoHistory
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.history.UndoOwner
import ru.hollowhorizon.hollowengine.client.ui.ide.GameViewportId
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeModel
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.IdeScenes
import ru.hollowhorizon.hollowengine.client.ui.ide.InspectorId
import ru.hollowhorizon.hollowengine.client.ui.ide.SceneId
import ru.hollowhorizon.hollowengine.client.ui.ide.TimelineId
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene.CutsceneEditorSessions
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.IdeTimelines
import ru.hollowhorizon.hollowengine.client.utils.IconHelper
import ru.hollowhorizon.hollowengine.client.utils.lang

/** A history the history window can show and the keys can reach, and what to call it there. */
internal class HistoryContext(val id: String, val title: String, val icon: String, val history: UndoHistory)

/** Which history Ctrl+Z and the history window go to. */
internal class IdeHistories(
    private val model: HollowIdeModel,
    /** The history of a text file, kept by its editor rather than by the file. */
    private val textHistory: (HollowIdeOpenFile) -> UndoHistory?,
) {
    /** The context the focus or the history window chose last. */
    private var chosen by mutableStateOf<String?>(null)

    fun all(): List<HistoryContext> = buildList {
        if (EditorMode.isAvailable()) add(HistoryContext(WORLD, "$LANG.world".lang, WORLD_ICON, WorldHistory.history))
        model.files.values.forEach { file ->
            val history = historyOf(file) ?: return@forEach
            add(HistoryContext(file.id, file.title, IconHelper.forPath(file.path).toString(), history))
        }

        val timeline = timelineHistory()
        if (none { it.history === timeline }) add(HistoryContext(TIMELINE, "$LANG.timeline".lang, TIMELINE_ICON, timeline))
    }

    /** The context of the window [focused], or the one chosen before. Reading it changes nothing. */
    fun resolve(focused: String?, contexts: List<HistoryContext> = all()): HistoryContext? =
        contextOf(focused, contexts) ?: contexts.firstOrNull { it.id == chosen } ?: contexts.firstOrNull()

    /** Makes the context of the window [focused] the one kept, when it has one. */
    fun follow(focused: String?) {
        contextOf(focused, all())?.let { chosen = it.id }
    }

    fun choose(context: HistoryContext) {
        chosen = context.id
    }

    private fun contextOf(focused: String?, contexts: List<HistoryContext>): HistoryContext? {
        val history = when (focused) {
            null -> null
            TimelineId -> timelineHistory()
            SceneId, InspectorId, GameViewportId ->
                IdeScenes.current?.history ?: WorldHistory.history.takeIf { EditorMode.isAvailable() }

            else -> model.files.values.firstOrNull { it.id == focused }?.let(::historyOf)
        } ?: return null
        return contexts.firstOrNull { it.history === history }
    }

    private fun historyOf(file: HollowIdeOpenFile): UndoHistory? = (file.document as? UndoOwner)?.history ?: textHistory(file)

    private fun timelineHistory(): UndoHistory =
        (IdeTimelines.current?.controller ?: CutsceneEditorSessions.default.timeline).history

    private companion object {
        const val LANG = UndoLabel.LANG
        const val WORLD = "history-world"
        const val TIMELINE = "history-timeline"
        const val WORLD_ICON = "hollowengine:textures/gui/icons/global.svg"
        const val TIMELINE_ICON = "hollowengine:textures/gui/icons/film.svg"
    }
}
