package ru.hollowhorizon.hollowengine.client.ui.ide.files.montage

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import ru.hollowhorizon.hollowengine.client.keymap.HollowKeys
import ru.hollowhorizon.hollowengine.client.models.internal.animator.*
import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.client.render.DebugSkeletonRenderer
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.GizmoToolbarButtons
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.PublishScene
import ru.hollowhorizon.hollowengine.client.ui.ide.SceneTarget
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeMontageDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorColors
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorIconButton
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorStylesheet
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.KeyInsertMenu
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.SegmentLane
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.TimelineKeys
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.PREVIEW_RESET_ICON
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.PlaybackPreview
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.playbackText
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.PublishTimeline
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.TimelineTarget
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.hasInspectableSelection
import ru.hollowhorizon.hollowengine.client.ui.inspector.PublishInspector
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeItem
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.utils.math.eulerRotationXyz
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.time.Duration.Companion.milliseconds

private const val AutoSaveDelayMillis = 900L

private const val PlayIcon = "hollowengine:textures/gui/icons/timeline/play.svg"
private const val PauseIcon = "hollowengine:textures/gui/icons/timeline/pause.svg"
private const val SkeletonIcon = "hollowengine:textures/gui/icons/rig/skeleton.svg"
private const val ColliderIcon = "hollowengine:textures/gui/icons/rig/colliders.svg"
private const val KeyIcon = "hollowengine:textures/gui/icons/timeline/key_add.svg"

/**
 * The editor for a `.montage` file.
 */
@Composable
internal fun MontageEditorPanel(file: HollowIdeOpenFile) {
    val document = file.document as HollowIdeMontageDocument
    val state = remember(document) { document.editorState { MontageEditorState(document, file.path) } }
    val session = state.session
    val rigState = state.rigState
    val viewer = rigState.viewer
    val model by viewer.modelFlow.collectAsState()

    LaunchedEffect(document.revision) {
        file.updateDirty(document.isModified)
        state.syncModel()
        session.sync()
        if (!document.isModified) return@LaunchedEffect
        delay(AutoSaveDelayMillis.milliseconds)
        if (document.isModified && file.save()) state.publish()
    }

    LaunchedEffect(document) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val delta = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                if (session.timeline.isPlaying) session.timeline.onUpdate(delta)
                state.frame++
            }
        }
    }

    viewer.debugBackdrop = { lines -> lines.floor() }
    viewer.debugFill = { shapes ->
        if (rigState.showSkeleton) {
            val colors = boneColors(viewer.attachment.rig, viewer.nodes)
            DebugSkeletonRenderer.fill(viewer.attachment, shapes, rigState.selected, colors::get)
        }
    }
    viewer.decalOverlay = { key -> rigState.partSelection.picksDecal(key) }
    viewer.debugDraw = { lines ->
        if (rigState.showSkeleton) lines.ikOverlay(viewer.attachment.rig, viewer.nodes, rigState.partSelection)
        if (rigState.showColliders) {
            lines.colliders(previewColliders(viewer.attachment.rig, viewer.nodes), rigState.partSelection)
            lines.soundAreas(viewer.attachment.rig, viewer.nodes, rigState.partSelection)
            lines.decalShapes(viewer.attachment.rig, viewer.nodes, rigState.partSelection)
        }
    }

    LaunchedEffect(rigState.selected, rigState.selectedPart, document.montage.attachments.size) {
        if (state.pick is MontagePick.Clip || state.pick is MontagePick.Playback) session.timeline.selectSegment(
            null,
            null
        )
        val bone = rigState.selected
        if (bone == null && rigState.selectedPart == null && state.sceneLane != null) return@LaunchedEffect
        state.sceneLane = null
        val part = rigState.selectedPart?.takeIf { state.rigs.rig.holder(bone).attachment(it) != null }
        val attachments = document.montage.attachments.filter { it.bone == bone }.map { it.spec.id }
        session.select(bone, attachments + listOfNotNull(part))
    }

    val bones = remember(model) { viewer.nodes.flatMap { node -> node.walk().map(RuntimeNode::name) } }
    val timelineHasSelection = session.timeline.hasInspectableSelection()
    PublishInspector(
        source = "montage-${file.path}",
        key = listOf(state.pick, rigState.selected, rigState.selectedPart, bones, timelineHasSelection),
    ) {
        if (timelineHasSelection) null else montageInspectorTarget(document, state, bones)
    }

    PublishScene(
        source = "montage-${file.path}",
        key = listOf(
            document.revision,
            model,
            rigState.selected,
            state.sceneLane,
            rigState.expanded.toList(),
            rigState.rootExpanded,
            viewer.nodeVisibilityRevision
        ),
    ) {
        montageSceneTarget(
            state,
            rigSceneTarget(state.rigs, rigState, state.title, addsBones = false, sceneId = "montage-bones")
        )
    }

    PublishTimeline(source = "montage-${file.path}", key = file.path) {
        TimelineTarget(
            id = "montage-${file.path}",
            controller = session.timeline,
            refresh = session::commit,
            recordable = true
        )
    }

    Column(
        tags = listOf("ide-file-panel"),
        modifier = Modifier.style(AnimatorStylesheet).background(AnimatorColors.Canvas).focusScope().onKeyInput { input ->
            if (!input.repeat && TimelineKeys.handlePlayback(session.timeline, input.key, input.modifiers)) input.consume()
        },
    ) {
        document.error?.let { message ->
            Text(
                montageText("broken_file") + " " + message,
                modifier = Modifier.size(100.percent).padding(6.px).fontSize(10f).foreground(AnimatorColors.Muted),
            )
        }
        Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 0.px).grow(1f)) {
            key(rigState) {
                RigViewport(state.rigs, rigState, null) { input ->
                    val keying = !input.repeat && HollowKeys.INSERT_KEY.matches(input)
                    if (keying) state.keyMenu = rigState.pointerOnSurface()
                    keying
                }
            }
            Toolbar(state)
            KeyInsertMenu(state.keyMenu, onPick = { parts -> session.keyPicked(rigState.selected, rigState.selectedPart, parts) }) {
                state.keyMenu = null
            }
            if (document.montage.previewModel.isBlank()) Text(
                montageText("no_preview_model"),
                modifier = Modifier.position(8.px, 32.px).fontSize(10f).foreground(AnimatorColors.Muted)
                    .inputTransparent(),
            )
        }
    }
}

@Composable
private fun Toolbar(state: MontageEditorState) {
    val timeline = state.session.timeline
    val rigState = state.rigState
    Row(tags = listOf("viewport-toolbar"), modifier = Modifier.position(8.px, 8.px)) {
        AnimatorIconButton(
            icon = if (timeline.isPlaying) PauseIcon else PlayIcon,
            tooltip = HollowKeys.PLAY.hint(montageText(if (timeline.isPlaying) "pause" else "play")),
            size = 12f,
            active = timeline.isPlaying,
        ) { timeline.isPlaying = !timeline.isPlaying }
        AnimatorIconButton(PREVIEW_RESET_ICON, playbackText("preview_reset"), size = 12f) { state.preview.reset() }
        AnimatorIconButton(KeyIcon, HollowKeys.INSERT_KEY.hint(montageText("key_picked")), size = 12f) {
            state.session.keyPicked(rigState.selected, rigState.selectedPart)
        }
        AnimatorIconButton(
            SkeletonIcon,
            "hollowengine.gui.rig_editor.toggle_skeleton".lang,
            size = 12f,
            active = rigState.showSkeleton
        ) {
            rigState.showSkeleton = !rigState.showSkeleton
        }
        AnimatorIconButton(
            ColliderIcon,
            "hollowengine.gui.rig_editor.toggle_colliders".lang,
            size = 12f,
            active = rigState.showColliders
        ) {
            rigState.showColliders = !rigState.showColliders
        }
        if (rigState.partSelection != null) GizmoToolbarButtons()
    }
}

/**
 * What the montage editor is looking at, as opposed to what it is editing: the preview model and its rig
 * editor state, the timeline, and what is picked on it.
 */
internal class MontageEditorState(val document: HollowIdeMontageDocument, private val path: String) {
    val title: String = path.substringAfterLast('/').substringBeforeLast('.')

    /** The clips, the events or the sections, when the scene picked them in place of a bone; null otherwise. */
    var sceneLane by mutableStateOf<String?>(null)

    /** Where the insert-key menu is open, at the pointer over the preview; null while it is closed. */
    var keyMenu by mutableStateOf<UiRect?>(null)

    /** What the montage hangs on its preview model, as the rig editors edit it. */
    val rigs = MontageRigEditing(document) { RigAssets.of(previewModel.takeIf(String::isNotBlank)?.rl) }

    val session = MontageTimelineSession(document, rigs).also { session ->
        session.clips = { rigState.viewer.animations.associate { it.name to it.duration } }
        session.onPick = { pick ->
            this.pick = pick
            if (pick is MontagePick.Attachment) rigState.select(pick.bone, pick.id)
        }
        rigs.bonePose = { bone ->
            RigPose(
                session.boneValue(bone, MontageBoneProperty.POSITION),
                eulerRotationXyz(session.boneValue(bone, MontageBoneProperty.ROTATION)),
                session.boneValue(bone, MontageBoneProperty.SCALE),
            )
        }
        rigs.time = { session.timeline.currentTime }
        rigs.recording = { session.timeline.isRecording }
        rigs.animated = session::isAnimated
        rigs.onFieldKey = session::keyField
        rigs.onBoneHold = session::holdBone
        rigs.onFieldHold = session::holdFields
        rigs.held = session::withHeldFields
        rigs.heldBones = session::withHeldBones
        rigs.onBonePose = session::keyPose
    }

    /** The data the preview plays the montage with, in place of an entity, which its sections' rules read. */
    val preview = PlaybackPreview().also { preview ->
        val context = AnimatorEvaluationContext()
        session.playback.condition = { source ->
            context.data = preview.values
            AnimatorExpressionEvaluator.condition(source, context)
        }
        preview.onReset = { session.timeline.applyCurrentTime(0f) }
    }

    /** The block or marker picked on the timeline, which the inspector shows instead of the scene's pick. */
    var pick by mutableStateOf<MontagePick?>(null)

    private var previewModel: String = document.montage.previewModel

    var rigState by mutableStateOf(rigStateFor(previewModel))
        private set

    /** Ticks every frame, so what is drawn over the preview follows the playhead. */
    var frame by mutableStateOf(0L)

    /** Builds the preview again when the montage names another model to show it on. */
    fun syncModel() {
        val wanted = document.montage.previewModel
        if (wanted == previewModel) return
        previewModel = wanted
        rigState = rigStateFor(wanted)
    }

    /** Hands the saved montage to both sides, so what plays it in the world plays it as it is now. */
    fun publish() {
        MontageAssets.put(montageId(path), document.montage)
    }

    private fun rigStateFor(model: String): RigEditorState = RigEditorState(rigs, model).also { state ->
        state.viewer.instance.animator.add(PreviewLayer())
        state.viewer.effectClock = { session.timeline.currentTime }
        state.viewer.playsSounds = true
        state.viewer.effectEmitting = { bone, id, seconds ->
            val montage = document.montage
            val own = montage.attachments.firstOrNull { it.bone == bone && it.spec.id == id }
                ?: montage.attachments.firstOrNull { it.bone == null && it.spec.id == id }
            own?.isActive(seconds) ?: true
        }
    }

    /** Plays the montage where the playhead is, and lays what it hangs on the model as it does in the world. */
    private inner class PreviewLayer : AnimationLayer {
        override val id: String = "montage-preview"
        override val priority: Int = MontageLayerSpec.PRIORITY
        override val blendMode: LayerBlendMode = LayerBlendMode.Override

        override fun sample(target: PoseTarget, context: AnimatorEvaluationContext): LayerPose {
            val montage = session.withHeldBones(document.montage)
            val time = session.timeline.currentTime.coerceIn(0f, montage.duration)
            context.montages += MontageMoment(montage, time, 1f)
            return LayerPose(MontageSampler.sample(montage, time, target, target.nodesByIndex.keys))
        }
    }
}

/** How alike two turns are, as the cosine of half the angle between them, for them to count as the same. */
private const val SAME_TURN = 0.99999f

private const val ClipsItem = "montage-clips"
private const val EventsItem = "montage-events"
private const val SectionsItem = "montage-sections"
private val VirtualItems = setOf(ClipsItem, EventsItem, SectionsItem)
private const val ClipsIcon = "hollowengine:textures/gui/icons/timeline/film.svg"
private const val EventsIcon = "hollowengine:textures/gui/icons/timeline/key_add.svg"
private const val SectionsIcon = "hollowengine:textures/gui/icons/loops.svg"

/**
 * The scene of the montage: the clips, the events and the sections as objects of their own above the model, which
 * are picked like bones and open their lanes on the timeline, and the model's bones with what the montage hangs on them.
 */
private fun montageSceneTarget(state: MontageEditorState, bones: SceneTarget): SceneTarget {
    val session = state.session
    fun item(id: String, label: String, icon: String) = UiTreeItem<Any?>(
        id = id,
        label = label,
        depth = 0,
        payload = null,
        icon = icon,
        hasChildren = false,
        expanded = false,
        selected = state.sceneLane == id,
    )

    fun pickLane(id: String, lane: SegmentLane) {
        state.sceneLane = id
        state.rigState.select(null)
        session.selectLane(lane)
    }
    return SceneTarget(
        id = bones.id,
        items = listOf(
            item(ClipsItem, "${montageText("group_clips")}  ●${state.document.montage.clips.size}", ClipsIcon),
            item(EventsItem, "${montageText("group_events")}  ●${state.document.montage.events.size}", EventsIcon),
            item(SectionsItem, "${playbackText("sections")}  ●${state.document.montage.sections.size}", SectionsIcon),
        ) + bones.items,
        onSelect = { id ->
            when (id) {
                ClipsItem -> pickLane(id, session.clipLane)
                EventsItem -> pickLane(id, session.playback.eventLane)
                SectionsItem -> pickLane(id, session.playback.sectionLane)
                else -> bones.onSelect(id)
            }
        },
        onToggle = bones.onToggle,
        hint = bones.hint,
        menu = { id ->
            when (id) {
                ClipsItem -> session.clips().keys.sorted().map { name ->
                    UiDropdownItem(name) {
                        session.addClip(
                            name,
                            session.timeline.currentTime,
                            session.clips().getValue(name)
                        )
                    }
                }.let { listOf(UiDropdownItem(montageText("add_clip"), children = it)) }

                EventsItem -> listOf(UiDropdownItem(playbackText("add_event")) { session.playback.addEvent(session.timeline.currentTime) })
                SectionsItem -> listOf(UiDropdownItem(playbackText("add_section")) { session.playback.addSection(session.timeline.currentTime) })
                else -> bones.menu?.invoke(id).orEmpty()
            }
        },
        onIconClick = { id -> if (id !in VirtualItems) bones.onIconClick?.invoke(id) },
        history = bones.history,
    )
}

/** The id a montage file goes by in scripts and animators: `<namespace>:montages/<name>`. */
internal fun montageId(path: String): String =
    path.substringAfter("assets/").replaceFirst("/", ":").removeSuffix(".montage")

internal fun montageKey(name: String): String = "hollowengine.gui.montage_editor.$name"

internal fun montageText(name: String): String = montageKey(name).lang
