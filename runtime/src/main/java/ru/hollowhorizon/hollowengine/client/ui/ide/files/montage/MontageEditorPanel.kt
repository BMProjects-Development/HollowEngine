package ru.hollowhorizon.hollowengine.client.ui.ide.files.montage

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.models.internal.animator.*
import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.client.render.DebugSkeletonRenderer
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.ide.PublishScene
import ru.hollowhorizon.hollowengine.client.ui.ide.SceneTarget
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeMontageDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorColors
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorIconButton
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorStylesheet
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.*
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.PublishTimeline
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.TimelineTarget
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.hasInspectableSelection
import ru.hollowhorizon.hollowengine.client.ui.inspector.PublishInspector
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTreeItem
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.utils.math.eulerDegreesXyz
import ru.hollowhorizon.hollowengine.common.utils.math.eulerRotationXyz
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

private const val AutoSaveDelayMillis = 900L

private const val PlayIcon = "hollowengine:textures/gui/icons/timeline/play.svg"
private const val PauseIcon = "hollowengine:textures/gui/icons/timeline/pause.svg"
private const val SkeletonIcon = "hollowengine:textures/gui/icons/rig/skeleton.svg"
private const val ColliderIcon = "hollowengine:textures/gui/icons/rig/colliders.svg"
private const val KeyIcon = "hollowengine:textures/gui/icons/timeline/key_add.svg"

private val GizmoModes = listOf(
    Triple(
        GizmoEditMode.TRANSLATE,
        "hollowengine:textures/gui/icons/gizmo_translate.svg",
        "hollowengine.gui.ide.gizmo.translate"
    ),
    Triple(
        GizmoEditMode.ROTATE,
        "hollowengine:textures/gui/icons/gizmo_rotate.svg",
        "hollowengine.gui.ide.gizmo.rotate"
    ),
    Triple(GizmoEditMode.SCALE, "hollowengine:textures/gui/icons/gizmo_scale.svg", "hollowengine.gui.ide.gizmo.scale"),
)

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
    viewer.debugDraw = { lines ->
        if (rigState.showSkeleton) lines.ikOverlay(viewer.attachment.rig, viewer.nodes, rigState.partSelection)
        if (rigState.showColliders) lines.colliders(
            previewColliders(viewer.attachment.rig, viewer.nodes),
            rigState.partSelection
        )
    }

    LaunchedEffect(rigState.selected, rigState.selectedPart, document.montage.attachments.size) {
        if (state.pick is MontagePick.Clip || state.pick is MontagePick.Event) session.timeline.selectSegment(
            null,
            null
        )
        val bone = rigState.selected
        val part = rigState.selectedPart
        if (bone != null) session.touchBone(bone)
        document.montage.attachments.filter { it.bone == bone }.forEach { session.touchAttachment(bone, it.spec.id) }
        if (part != null && state.rigs.rig.holder(bone).attachment(part) != null) session.touchAttachment(bone, part)
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
        modifier = Modifier.style(AnimatorStylesheet).background(AnimatorColors.Canvas).focusScope(),
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
                    val keying = input.key == GLFW.GLFW_KEY_K && !input.control && !input.alt && !input.repeat
                    if (keying) session.keyPicked(rigState.selected, rigState.selectedPart)
                    keying
                }
            }
            Toolbar(state)
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
            tooltip = montageText(if (timeline.isPlaying) "pause" else "play"),
            size = 12f,
            active = timeline.isPlaying,
        ) { timeline.isPlaying = !timeline.isPlaying }
        AnimatorIconButton(KeyIcon, montageText("key_picked"), size = 12f) {
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
        if (rigState.partSelection != null) {
            GizmoModes.forEach { (mode, icon, tooltip) ->
                AnimatorIconButton(icon, tooltip.lang, size = 12f, active = mode in rigState.gizmoModes) {
                    rigState.gizmoModes =
                        if (mode in rigState.gizmoModes) rigState.gizmoModes - mode else rigState.gizmoModes + mode
                }
            }
        }
    }
}

/**
 * What the montage editor is looking at, as opposed to what it is editing: the preview model and its rig
 * editor state, the timeline, and what is picked on it.
 */
internal class MontageEditorState(val document: HollowIdeMontageDocument, private val path: String) {
    val title: String = path.substringAfterLast('/').substringBeforeLast('.')

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
        rigs.onBonePose = { bone, pose ->
            session.keyBone(bone, MontageBoneProperty.POSITION, pose.position)
            val turned = eulerRotationXyz(session.boneValue(bone, MontageBoneProperty.ROTATION))
            if (abs(turned.dot(pose.rotation)) < SAME_TURN) session.keyBone(
                bone,
                MontageBoneProperty.ROTATION,
                pose.rotation.eulerDegreesXyz()
            )
            session.keyBone(bone, MontageBoneProperty.SCALE, pose.scale)
        }
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
            val montage = document.montage
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
private const val ClipsIcon = "hollowengine:textures/gui/icons/timeline/film.svg"
private const val EventsIcon = "hollowengine:textures/gui/icons/timeline/key_add.svg"

/**
 * The scene of the montage: the clips and the events as objects of their own above the model, which open their
 * lanes on the timeline when picked, and the model's bones with what the montage hangs on them.
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
        selected = false,
    )
    return SceneTarget(
        id = bones.id,
        items = listOf(
            item(ClipsItem, "${montageText("group_clips")}  ●${state.document.montage.clips.size}", ClipsIcon),
            item(EventsItem, "${montageText("group_events")}  ●${state.document.montage.events.size}", EventsIcon),
        ) + bones.items,
        onSelect = { id ->
            when (id) {
                ClipsItem -> session.show(session.clipLane)
                EventsItem -> session.show(session.eventLane)
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

                EventsItem -> listOf(UiDropdownItem(montageText("add_event")) { session.addEvent(session.timeline.currentTime) })
                else -> bones.menu?.invoke(id).orEmpty()
            }
        },
        onIconClick = { id -> if (id != ClipsItem && id != EventsItem) bones.onIconClick?.invoke(id) },
        history = bones.history,
    )
}

/** The id a montage file goes by in scripts and animators: `<namespace>:montages/<name>`. */
internal fun montageId(path: String): String =
    path.substringAfter("assets/").replaceFirst("/", ":").removeSuffix(".montage")

internal fun montageKey(name: String): String = "hollowengine.gui.montage_editor.$name"

internal fun montageText(name: String): String = montageKey(name).lang
