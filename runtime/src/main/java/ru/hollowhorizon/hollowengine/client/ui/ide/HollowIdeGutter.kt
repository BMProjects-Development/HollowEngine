package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiGutterMarker
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.scripting.execution.ScriptExecutionStatus
import ru.hollowhorizon.hollowengine.common.scripting.ide.DefinitionLocation
import ru.hollowhorizon.hollowengine.common.scripting.ide.EditorStructure
import ru.hollowhorizon.hollowengine.common.scripting.ide.GutterMarkerKind

@Composable
internal fun ideGutterMarkers(
    file: HollowIdeOpenFile,
    structure: EditorStructure,
    save: () -> Boolean,
    navigate: (List<Pair<String, DefinitionLocation>>) -> Unit,
): List<UiGutterMarker> {
    val runnable = !file.readOnly && structure.runTargets.isNotEmpty()
    val canRun = runnable && ScriptExecutionClient.allowed
    val execution = ScriptExecutionClient.states[file.path]
    val analyzedText = file.text
    return remember(file, analyzedText, structure, execution, canRun) {
        buildList {
            structure.markers.forEach { marker ->
                val kind = marker.kind.name.lowercase()
                add(UiGutterMarker("$kind-${marker.offset}", marker.offset,
                    "hollowengine:textures/gui/icons/gutter/$kind.svg", "hollowengine.gui.ide.gutter.$kind".lang,
                    listOf("gutter-$kind"), onClick = if (marker.targets.isEmpty()) null else {
                        { if (file.text == analyzedText) navigate(marker.targets.map { it.label to it.location }) }
                    }))
            }
            if (canRun) structure.runTargets.forEach { target ->
                val status = execution?.status ?: ScriptExecutionStatus.IDLE
                val active = status != ScriptExecutionStatus.IDLE
                val stopping = status == ScriptExecutionStatus.STOPPING
                val key = when {
                    stopping -> "stopping"
                    active -> "stop"
                    target.state != null -> "run_state"
                    else -> "run"
                }
                add(UiGutterMarker("run-${target.offset}", target.offset,
                    "hollowengine:textures/gui/icons/${if (active) "stop" else "play"}.svg",
                    "hollowengine.gui.ide.gutter.$key".lang(*(target.state?.let { arrayOf(it) } ?: emptyArray())),
                    listOf("gutter-run"), onClick = if (stopping) null else {
                        {
                            if (file.text == analyzedText) {
                                if (active) ScriptExecutionClient.stop(file.path)
                                else if (!file.dirty || save()) ScriptExecutionClient.run(file.path, target.state)
                            }
                        }
                    }))
            }
        }
    }
}
