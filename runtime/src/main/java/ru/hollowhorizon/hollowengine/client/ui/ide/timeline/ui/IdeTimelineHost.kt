package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui

import androidx.compose.runtime.*
import kotlinx.coroutines.isActive
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.CutsceneIcon
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineController
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineKeys
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorStylesheet
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorTarget
import ru.hollowhorizon.hollowengine.client.ui.inspector.PublishInspector
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.utils.rl

/**
 * What the timeline window is showing.
 */
class TimelineTarget(
    val id: String,
    val controller: TimelineController,
    /** Called when the editor changed something the owner has to write down. */
    val refresh: () -> Unit,
    val onKeyInput: (Int, Int) -> Boolean = { _, _ -> false },
    /** What the capture button does, or null when this target has nothing to capture. */
    val onCapture: (() -> Unit)? = null,
    /** Whether the owner turns edits into keys while [TimelineController.isRecording] is on. */
    val recordable: Boolean = false,
    val onSave: (() -> Unit)? = null,
    val onLoad: (() -> Unit)? = null,
    /** Advances whatever the timeline is driving, in seconds, while it is playing. */
    val advance: ((Float) -> Unit)? = null,
    /** Dialogs and chrome the owner wants drawn over the timeline. */
    val overlay: HollowUiContent? = null,
)

/**
 * What the timeline window shows while no editor has published a [TimelineTarget], such as the
 * cutscene being authored. It draws the whole window itself.
 */
interface DefaultTimeline {
    /** The timeline drawn, whose history the window's undo steps through. */
    val controller: TimelineController

    @Composable
    fun Content(keyboardActive: Boolean)
}

/**
 * Whose timeline the shared window shows, wherever it is drawn.
 *
 * Last publisher wins, the same way the inspector works.
 */
object IdeTimelines {
    var current: TimelineTarget? by mutableStateOf(null)
        private set

    /** Contributed by addons; the first one is shown while nothing is published. */
    val defaults = ExtensionPoints.create("hollowengine:ide/default-timelines".rl, DefaultTimeline::class)

    val default: DefaultTimeline? get() = defaults.extensions.firstOrNull()

    private var owner: String? = null

    fun publish(source: String, target: TimelineTarget?) {
        if (target == null) {
            release(source)
            return
        }
        if (owner == source && current?.id == target.id) return
        owner = source
        current = target
    }

    fun release(source: String) {
        if (owner != source) return
        owner = null
        current = null
    }
}

/**
 * Keeps [target] in the timeline window for as long as this editor is composed.
 */
@Composable
fun PublishTimeline(source: String, key: Any?, target: () -> TimelineTarget?) {
    LaunchedEffect(source, key) { IdeTimelines.publish(source, target()) }
    DisposableEffect(source) { onDispose { IdeTimelines.release(source) } }
}

@Composable
fun TimelineDock(keyboardActive: Boolean = true) {
    val target = IdeTimelines.current
    if (target == null) {
        val fallback = IdeTimelines.default
        if (fallback != null) fallback.Content(keyboardActive) else EmptyTimeline()
        return
    }

    val controller = target.controller

    val revision = remember(target.id) { mutableStateOf(0) }
    val refresh: () -> Unit = remember(target) {
        {
            target.refresh()
            revision.value++
        }
    }
    revision.value

    LaunchedEffect(target.id) {
        var lastNanos = -1L
        while (isActive) {
            withFrameNanos { now ->
                val delta = if (lastNanos < 0L) 0f
                else ((now - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
                lastNanos = now

                if (controller.isPlaying) target.advance?.invoke(delta)
                if (controller.curveAxis.advance(now)) revision.value++
            }
        }
    }

    PublishInspector(source = "timeline-${target.id}", key = controller.selectionKey()) {
        if (!controller.hasInspectableSelection()) return@PublishInspector null
        InspectorTarget(
            id = "timeline-${target.id}-${controller.selectionKey()}",
            title = TimelineLang.PROPERTIES.lang,
            icon = CutsceneIcon,
        ) {
            revision.value
            TimelineSelectionFields(controller, refresh)
        }
    }

    CompositionLocalProvider(LocalTimelineRevision provides revision.value) {
        Box(id = "ide-timeline-dock", mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 100.percent)) {
            HollowTimelineEditor(
                controller = controller,
                refresh = refresh,
                onKeyInput = { key, modifiers ->
                    val handled = target.onKeyInput(key, modifiers) || TimelineKeys.handle(controller, key, modifiers)
                    if (handled) refresh()
                    handled
                },
                keyboardActive = keyboardActive,
                onCapture = { target.onCapture?.invoke() },
                onSave = { target.onSave?.invoke() },
                onLoad = { target.onLoad?.invoke() },
                features = TimelineFeatures(
                    capture = target.onCapture != null,
                    storage = target.onSave != null,
                    cameraPreview = false,
                    record = target.recordable,
                ),
            )
            target.overlay?.invoke()
        }
    }
}

/**
 * Timeline editor as a part of some other window.
 */
@Composable
fun EmbeddedTimelineEditor(
    controller: TimelineController,
    onChanged: () -> Unit,
    features: TimelineFeatures,
    modifier: Modifier = Modifier.size(100.percent, 100.percent),
    selectionPanel: Boolean = true,
) {
    val revision = remember(controller) { mutableStateOf(0) }
    val refresh: () -> Unit = remember(controller) {
        {
            onChanged()
            revision.value++
        }
    }
    revision.value

    LaunchedEffect(controller) {
        while (isActive) {
            withFrameNanos { now -> if (controller.curveAxis.advance(now)) revision.value++ }
        }
    }

    CompositionLocalProvider(LocalTimelineRevision provides revision.value) {
        Row(modifier = modifier) {
            Box(mode = UiBoxMode.STACK, modifier = Modifier.size(0.px, 100.percent).grow(1f)) {
                HollowTimelineEditor(
                    controller = controller,
                    refresh = refresh,
                    onKeyInput = { key, modifiers ->
                        TimelineKeys.handle(controller, key, modifiers).also { handled -> if (handled) refresh() }
                    },
                    keyboardActive = true,
                    features = features,
                )
            }
            if (selectionPanel) {
                Column(
                    tags = listOf("insp-panel"),
                    modifier = Modifier.size(EmbeddedSelectionWidth.px, 100.percent).style(InspectorStylesheet)
                        .scrollable(horizontal = false),
                ) {
                    revision.value
                    TimelineSelectionFields(controller, refresh)
                }
            }
        }
    }
}

private const val EmbeddedSelectionWidth = 220f

@Composable
private fun EmptyTimeline() {
    Box(
        id = "ide-timeline-empty",
        mode = UiBoxMode.STACK,
        modifier = Modifier.size(100.percent, 100.percent).background(TimelineColors.Background),
    ) {
        Text(
            TimelineLang.EMPTY.lang,
            modifier = Modifier.align(horizontal = UiAlign.CENTER, vertical = UiAlign.CENTER)
                .fontSize(11f)
                .foreground(TimelineColors.Muted),
        )
    }
}
