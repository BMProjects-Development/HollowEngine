package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.HollowEngineBuild
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdeFileView
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdeViewMode
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorTarget
import ru.hollowhorizon.hollowengine.client.ui.style.UiTextOverflow
import ru.hollowhorizon.hollowengine.client.ui.widgets.TextFieldState
import ru.hollowhorizon.hollowengine.client.utils.IconHelper
import ru.hollowhorizon.hollowengine.client.utils.lang

/** One step of the status bar's path; [onClick] shows it where it lives, a panel or the project tree. */
internal class HollowIdeCrumb(val label: String, val icon: String? = null, val onClick: (() -> Unit)? = null)

/** Where the status bar's crumbs lead. */
internal class HollowIdeStatusNavigation(
    /** Selects [path] in the project tree, opening its folders and the tree itself; "" is the root. */
    val revealInProject: (path: String) -> Unit,
    val showWindow: (HollowIdeToolWindow) -> Unit,
    /** Where the thing the inspector shows came from, if anything is open to show it. */
    val showInspectedSource: () -> Unit,
)

/** Where the user is, as the status bar tells it: a path on the left, facts about it on the right. */
internal class HollowIdeStatus(
    val crumbs: List<HollowIdeCrumb>,
    val details: List<String> = emptyList(),
)

@Composable
internal fun HollowIdeStatusBar(status: HollowIdeStatus, message: String) {
    Row(id = "ide-status-bar", modifier = Modifier.size(100.percent, StatusBarHeight.px)) {
        Row(tags = listOf("ide-breadcrumbs"), modifier = Modifier.size(0.px, 100.percent).grow(1f).clip()) {
            status.crumbs.forEachIndexed { index, crumb ->
                if (index > 0) Box(tags = listOf("ide-breadcrumb-separator"))
                Crumb(index, crumb, last = index == status.crumbs.lastIndex)
            }
        }
        if (message.isNotEmpty()) {
            Text(
                message,
                tags = listOf("ide-status-message"),
                modifier = Modifier.textWrap(false).textOverflow(UiTextOverflow.DOTS),
            )
        }
        status.details.forEach { detail -> Text(detail, tags = listOf("ide-status-detail")) }
        Text("HollowEngine ${HollowEngineBuild.VERSION}", tags = listOf("ide-status-detail", "version"))
    }
}

@Composable
private fun Crumb(index: Int, crumb: HollowIdeCrumb, last: Boolean) {
    val onClick = crumb.onClick
    Row(
        id = "ide-breadcrumb-$index",
        tags = listOfNotNull("ide-breadcrumb", "last".takeIf { last }, "link".takeIf { onClick != null }),
        modifier = if (onClick == null) Modifier else Modifier.input(hoverable = true, clickable = true)
            .cursor(UiCursorShape.HAND)
            .onClick { event ->
                onClick()
                event.consume()
            },
    ) {
        crumb.icon?.let { icon -> Image(icon, tags = listOf("ide-breadcrumb-icon")) }
        Text(crumb.label, tags = listOf("ide-breadcrumb-label"), modifier = Modifier.textWrap(false))
    }
}

/** A file open in an editor: its place in the project, then the caret. */
internal fun fileStatus(
    project: HollowIdeCrumb,
    file: HollowIdeOpenFile,
    editor: TextFieldState?,
    navigation: HollowIdeStatusNavigation,
    view: HollowIdeFileView? = null,
): HollowIdeStatus {
    val icon = IconHelper.forPath(file.path, false).toString()
    val path = if (file.virtual) {
        val resources = HollowIdeCrumb(StatusLang.RESOURCES.lang, AssetManagerIcon) {
            navigation.showWindow(HollowIdeToolWindows.AssetManager)
        }
        pathCrumbs(resources, file.path.removePrefix(ResourcePrefix), icon, reveal = null)
    } else {
        pathCrumbs(project, file.path, icon, navigation.revealInProject)
    }
    val inside = view?.takeIf { it.mode != HollowIdeViewMode.TEXT }?.crumbs.orEmpty()
    val crumbs = path + inside.map { HollowIdeCrumb(it.label, it.icon, it.onClick) }
    val details = buildList {
        if (editor != null && file.textOrNull != null) add(caretLabel(editor.text, editor.caret))
        if (file.readOnly) add(StatusLang.READ_ONLY.lang)
    }
    return HollowIdeStatus(crumbs, details)
}

/** Whatever the inspector is showing. */
internal fun inspectorStatus(
    project: HollowIdeCrumb,
    target: InspectorTarget,
    navigation: HollowIdeStatusNavigation,
): HollowIdeStatus {
    val crumbs = listOfNotNull(
        project,
        HollowIdeCrumb(HollowIdeToolWindows.Inspector.title, OptionsIcon) {
            navigation.showWindow(HollowIdeToolWindows.Inspector)
        },
        HollowIdeCrumb(target.title, target.icon, navigation.showInspectedSource),
        target.subtitle?.let { HollowIdeCrumb(it, onClick = navigation.showInspectedSource) },
    )
    return HollowIdeStatus(crumbs)
}

/**
 * [root], then each segment of [path]; only the last one, the target itself, gets [icon]. With
 * [reveal], a click on a segment shows the folder or file it names.
 */
internal fun pathCrumbs(
    root: HollowIdeCrumb,
    path: String,
    icon: String?,
    reveal: ((String) -> Unit)?,
): List<HollowIdeCrumb> {
    val segments = path.split('/').filter { it.isNotEmpty() }
    return listOf(root) + segments.mapIndexed { index, segment ->
        val prefix = segments.subList(0, index + 1).joinToString("/")
        HollowIdeCrumb(segment, icon.takeIf { index == segments.lastIndex }, reveal?.let { { it(prefix) } })
    }
}

/** The caret as a one-based line and column. */
internal fun caretLabel(text: String, caret: Int): String {
    val at = caret.coerceIn(0, text.length)
    var line = 1
    var lineStart = 0
    for (index in 0 until at) {
        if (text[index] != '\n') continue
        line++
        lineStart = index + 1
    }
    return StatusLang.CARET.lang(line, at - lineStart + 1)
}

private const val StatusBarHeight = 20f
private const val ResourcePrefix = "resource://"

internal object StatusLang {
    private const val ROOT = "hollowengine.gui.ide.status."

    const val RESOURCES = ROOT + "resources"
    const val READ_ONLY = ROOT + "read_only"
    const val CARET = ROOT + "caret"
}
