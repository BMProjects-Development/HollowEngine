package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.UiTextOverflow
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextDiagnostic
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextDiagnosticSeverity
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.IconHelper
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.generated.Assets

/**
 * What the Problems page reports on: the file edited last, its diagnostics, and where a click on
 * one of them leads.
 */
internal class HollowIdeProblems(
    val file: HollowIdeOpenFile?,
    val diagnostics: List<UiTextDiagnostic>,
    val onOpen: (UiTextDiagnostic) -> Unit,
) {
    val errors: Int = diagnostics.count { it.severity == UiTextDiagnosticSeverity.ERROR }
    val warnings: Int = diagnostics.count { it.severity == UiTextDiagnosticSeverity.WARNING }

    /** Colors a count by the worst thing in it. */
    val severityTag: String
        get() = when {
            errors > 0 -> "has-errors"
            warnings > 0 -> "has-warnings"
            else -> "has-infos"
        }
}

/** The editor's corner summary; a click brings up the Problems page. */
@Composable
internal fun HollowIdeDiagnosticsBadge(
    fileId: String,
    diagnostics: List<UiTextDiagnostic>,
    onOpen: () -> Unit,
) {
    if (diagnostics.isEmpty()) return
    val errors = diagnostics.count { it.severity == UiTextDiagnosticSeverity.ERROR }
    val warnings = diagnostics.count { it.severity == UiTextDiagnosticSeverity.WARNING }
    Row(
        id = "diagnostics-badge-$fileId",
        tags = listOf("ide-diagnostics-badge", if (errors > 0) "has-errors" else "has-warnings"),
        modifier = Modifier.align(UiAlign.END, UiAlign.START)
            .input(clickable = true, hoverable = true)
            .cursor(UiCursorShape.HAND)
            .tooltipOnHover(ProblemsLang.SHOW.lang)
            .onClick { event ->
                onOpen()
                event.consume()
            }
    ) {
        SeverityCounts(errors, warnings)
    }
}

@Composable
internal fun HollowIdeProblemsList(problems: HollowIdeProblems) {
    val file = problems.file
    if (file == null) {
        ProblemsEmpty(ProblemsLang.NO_FILE.lang)
        return
    }
    val diagnostics = problems.diagnostics
    Row(tags = listOf("problems-file")) {
        Image(IconHelper.forPath(file.path, false).toString(), tags = listOf("problems-file-icon"))
        Text(file.title, tags = listOf("problems-file-name"))
        SeverityCounts(problems.errors, problems.warnings)
        Box(modifier = Modifier.size(0.px, 1.px).grow(1f))
        if (diagnostics.isNotEmpty()) {
            CopyButton("problems-copy-all", ProblemsLang.COPY_ALL.lang) {
                diagnostics.joinToString("\n", transform = UiTextDiagnostic::clipboardLine)
            }
        }
    }
    if (diagnostics.isEmpty()) {
        ProblemsEmpty(ProblemsLang.NONE.lang)
        return
    }
    Column(
        tags = listOf("problems-list"),
        modifier = Modifier.size(100.percent, 0.px).grow(1f).scrollable(),
    ) {
        diagnostics.forEachIndexed { index, diagnostic ->
            key(index) { ProblemRow(index, diagnostic, problems.onOpen) }
        }
    }
}

@Composable
private fun ProblemRow(index: Int, diagnostic: UiTextDiagnostic, onOpen: (UiTextDiagnostic) -> Unit) {
    Row(
        id = "problem-$index",
        tags = listOf("problem-row", diagnostic.severity.name.lowercase()),
        modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND)
            .onClick { event ->
                onOpen(diagnostic)
                event.consume()
            },
    ) {
        Image(diagnostic.severity.icon, tags = listOf("problem-icon"))
        Text(
            diagnostic.message.lineSequence().first(),
            tags = listOf("problem-message"),
            modifier = Modifier.textWrap(false).textOverflow(UiTextOverflow.DOTS),
        )
        Text(diagnostic.locationLabel(), tags = listOf("problem-location"))
        CopyButton("problem-copy-$index", ProblemsLang.COPY.lang) { diagnostic.clipboardLine() }
    }
}

@Composable
private fun SeverityCounts(errors: Int, warnings: Int) {
    if (errors > 0) {
        Image(UiTextDiagnosticSeverity.ERROR.icon, tags = listOf("problem-count-icon"))
        Text(errors.toString(), tags = listOf("problem-count", "has-errors"))
    }
    if (warnings > 0) {
        Image(UiTextDiagnosticSeverity.WARNING.icon, tags = listOf("problem-count-icon"))
        Text(warnings.toString(), tags = listOf("problem-count", "has-warnings"))
    }
}

@Composable
private fun ProblemsEmpty(message: String) {
    Box(tags = listOf("problems-empty"), modifier = Modifier.size(100.percent, 0.px).grow(1f)) {
        Text(message)
    }
}

@Composable
private fun CopyButton(id: String, tooltip: String, text: () -> String) = IconButton(
    Assets.Hollowengine.Textures.Gui.Icons.COPY.toString(),
    tooltip,
    id = id,
    tags = listOf("problem-copy"),
) { Minecraft.getInstance().keyboardHandler.clipboard = text() }

private val UiTextDiagnosticSeverity.icon: String
    get() = when (this) {
        UiTextDiagnosticSeverity.ERROR -> Assets.Hollowengine.Textures.Gui.Icons.ERROR.toString()
        UiTextDiagnosticSeverity.WARNING -> Assets.Hollowengine.Textures.Gui.Icons.WARN.toString()
        UiTextDiagnosticSeverity.INFO -> InfoIcon
    }

private fun UiTextDiagnostic.locationLabel(): String {
    return if (line > 0 && column > 0) "$line:$column" else "-"
}

private fun UiTextDiagnostic.clipboardLine(): String = "${locationLabel()} ${severity.name}: $message"

private const val InfoIcon = "hollowengine:textures/gui/icons/info.svg"

internal object ProblemsLang {
    private const val ROOT = "hollowengine.gui.ide.problems."

    const val SHOW = ROOT + "show"
    const val NO_FILE = ROOT + "no_file"
    const val NONE = ROOT + "none"
    const val COPY = ROOT + "copy"
    const val COPY_ALL = ROOT + "copy_all"
}
