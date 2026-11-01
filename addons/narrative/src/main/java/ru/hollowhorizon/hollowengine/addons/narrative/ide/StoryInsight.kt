package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraphCompiler
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActionFunctions
import ru.hollowhorizon.hollowengine.client.ui.ide.KotlinFragmentContext
import ru.hollowhorizon.hollowengine.client.ui.ide.KotlinFragmentInsight
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCodeInsight

/** The Kotlin analysis of stories. A field is analyzed as a function added to the script its story generates. */
internal object StoryInsight {
    private const val FIELD_START = "\nsuspend fun storyField() {\n"
    private const val FIELD_END = "\n}\n"
    private val insight by lazy { KotlinFragmentInsight("story.generated.kts") }

    /** Colors the script a story generates. */
    fun script(): UiCodeInsight = insight.file()

    /** Completes a field of a story in [state]. */
    fun field(state: StoryEditorState): UiCodeInsight = insight.completions {
        val graph = state.read() ?: StoryGraph()
        KotlinFragmentContext(StoryGraphCompiler.compile(graph, StoryActionFunctions).code + FIELD_START, FIELD_END)
    }
}
