package ru.hollowhorizon.hollowengine.addons.narrative.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryGraph
import ru.hollowhorizon.hollowengine.addons.narrative.graph.StoryNode
import ru.hollowhorizon.hollowengine.addons.narrative.ide.list.StoryCardKind
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewContext
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewProblem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextDiagnostic
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextDiagnosticSeverity

/**
 * Tells the IDE what is wrong with the story for as long as the editor is shown.
 *
 * @param reveal shows the node a problem is about.
 */
@Composable
internal fun PublishStoryProblems(context: HollowIdePreviewContext, graph: StoryGraph, checks: List<StoryCheck>, reveal: (String) -> Unit) {
    SideEffect {
        context.publishProblems(checks.map { check ->
            val severity = if (check.warning) UiTextDiagnosticSeverity.WARNING else UiTextDiagnosticSeverity.ERROR
            val owner = check.node?.let(graph::node)?.let(::titleOf)
            val message = owner?.let { "$it: ${check.message}" } ?: check.message
            HollowIdePreviewProblem(UiTextDiagnostic(0, 0, message, severity)) { check.node?.let(reveal) }
        })
    }
    DisposableEffect(context) { onDispose { context.publishProblems(emptyList()) } }
}

private fun titleOf(node: StoryNode): String =
    (node as? StoryNode.Call)?.let(StoryCatalog::signatureOf)?.let(StoryCatalog::title) ?: StoryCardKind.of(node).title
