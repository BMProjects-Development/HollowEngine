package ru.hollowhorizon.hollowengine.client.ui.ide.files.montage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.Column
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeMontageDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimationExpressionEditing
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.AttachmentAnimation
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.PlaybackPreview
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.PreviewDataSection
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.playback.playbackInspectorTarget
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.HolderFields
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.LocalAttachmentAnimation
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
import ru.hollowhorizon.hollowengine.common.models.*

private const val MontageIcon = "hollowengine:textures/gui/icons/files/rig.svg"
private const val ClipIcon = "hollowengine:textures/gui/icons/timeline/film.svg"
private const val BoneIcon = "hollowengine:textures/gui/icons/graph.svg"

private val ModelExtensions = listOf("gltf", "glb", "geo.json", "fbx", "bbmodel")

/**
 * What the inspector shows for the montage editor: a clip or an event picked on the timeline, or else the bone
 * picked in the scene, with what the montage hangs on it; for the model itself, the montage's own settings too.
 */
internal fun montageInspectorTarget(
    document: HollowIdeMontageDocument,
    state: MontageEditorState,
    bones: List<String>,
): InspectorTarget {
    when (val pick = state.pick) {
        is MontagePick.Clip -> return InspectorTarget(
            id = "montage-clip-${pick.id}",
            title = document.montage.clips.firstOrNull { it.id == pick.id }?.animation ?: pick.id,
            icon = ClipIcon,
            subtitle = montageText("section_clip"),
        ) { ClipFields(document, state, pick.id) }

        is MontagePick.Playback -> return playbackInspectorTarget(
            lanes = state.session.playback,
            pick = pick.pick,
            conditions = AnimationExpressionEditing,
            eventHint = montageText("event_hint"),
            onRemoved = { state.pick = null },
        )

        is MontagePick.Attachment, null -> Unit
    }

    val rigState = state.rigState
    val bone = rigState.selected
    return InspectorTarget(
        id = "montage-bone-${bone ?: ""}",
        title = bone ?: state.title,
        icon = if (bone == null) MontageIcon else BoneIcon,
        subtitle = montageText(if (bone == null) "section_montage" else "section_bone"),
    ) {
        CompositionLocalProvider(
            LocalEditorBones provides bones,
            LocalAttachmentAnimation provides MontageAttachmentAnimation(state),
        ) {
            if (bone == null) MontageFields(document, state.preview) else BoneFields(state, bone)
            key(bone) {
                HolderFields(
                    state.rigs,
                    bone,
                    rigState.selectedPart,
                    onSelectBone = rigState::select,
                    boneFields = false
                )
            }
        }
    }
}

@Composable
private fun MontageFields(document: HollowIdeMontageDocument, preview: PlaybackPreview) {
    val montage = document.montage
    Column(tags = listOf("insp-body")) {
        Section(montageText("section_montage")) {
            FieldLabel(montageText("preview_model"), montageText("preview_model_hint"))
            AssetPathField(
                value = montage.previewModel,
                label = montageText("preview_model"),
                id = "montage-preview-model",
                candidates = { host -> host.assets(ModelExtensions) },
            ) { value -> document.edit(mergeKey = "preview-model") { it.copy(previewModel = value.trim()) } }
            NumberRow(montageText("duration"), montage.duration, min = 0.05f) { value ->
                document.edit(mergeKey = "duration") { it.copy(duration = value) }
            }
        }
        PreviewDataSection(preview)
    }
}

/**
 * Where the montage moves [bone] at the playhead. Editing a value keys it there, as the bone gizmo does; the key
 * button starts animating a part of the bone with a key holding what it is now, or takes its keys away.
 */
@Composable
private fun BoneFields(state: MontageEditorState, bone: String) {
    val session = state.session
    session.timeline.currentTime
    Column(tags = listOf("insp-body")) {
        Section(montageText("section_bone_keys"), help = montageText("bone_keys_hint")) {
            PoseParts.forEach { property ->
                key(property) {
                    val animated = session.isBoneAnimated(bone, property)
                    KeyedField(
                        "bone-${property.name}",
                        animated,
                        onToggle = { session.toggleBone(bone, property) }) {
                        Vec3Row(
                            montageText("bone_${property.name.lowercase()}"),
                            session.boneValue(bone, property),
                            "bone-$bone-${property.name}",
                            step = if (property == MontageBoneProperty.ROTATION) 1.0 else null,
                        ) { value ->
                            session.editBone(bone, property, value)
                        }
                    }
                }
            }
            KeyedField(
                "bone-visible",
                session.isBoneAnimated(bone, MontageBoneProperty.VISIBLE),
                onToggle = {
                    session.toggleBone(bone, MontageBoneProperty.VISIBLE)
                }) {
                ToggleRow(montageText("bone_visible"), session.isBoneShown(bone)) { shown ->
                    session.editBoneShown(bone, shown)
                }
            }
        }
    }
}

@Composable
private fun ClipFields(document: HollowIdeMontageDocument, state: MontageEditorState, id: String) {
    val clip = document.montage.clips.firstOrNull { it.id == id } ?: return Hint(montageText("clip_removed"))
    val session = state.session
    fun edit(change: (MontageClip) -> MontageClip) = session.editClip(id, change)

    Column(tags = listOf("insp-body")) {
        Section(montageText("section_clip")) {
            val names = state.rigState.viewer.animations.map { it.name }
            TextRow(montageText("animation"), clip.animation, completions = assetCompletions(names)) { value ->
                edit { it.copy(animation = value.trim()) }
            }
            NumberRow(montageText("start"), clip.start, min = 0f) { value -> edit { it.copy(start = value) } }
            NumberRow(montageText("length"), clip.length, min = 0.05f) { value -> edit { it.copy(length = value) } }
            NumberRow(montageText("from"), clip.from, min = 0f) { value -> edit { it.copy(from = value) } }
            NumberRow(montageText("speed"), clip.speed) { value -> edit { it.copy(speed = value) } }
            ToggleRow(
                montageText("loop"),
                clip.loop,
                hint = montageText("loop_hint")
            ) { value -> edit { it.copy(loop = value) } }
            NumberRow(
                montageText("fade_in"),
                clip.fadeIn,
                min = 0f,
                help = montageText("fade_hint")
            ) { value -> edit { it.copy(fadeIn = value) } }
            NumberRow(montageText("fade_out"), clip.fadeOut, min = 0f) { value -> edit { it.copy(fadeOut = value) } }
            KeyedField(
                "clip-weight",
                session.isWeightAnimated(id),
                onToggle = { session.toggleWeight(id) }) {
                FieldLabel(
                    montageText("weight") + ": " + montageText(if (session.isWeightAnimated(id)) "weight_keyed" else "weight_full"),
                    montageText("weight_hint"),
                )
            }
            TextRow(
                montageText("mask"),
                clip.mask.text(),
                completions = assetCompletions(LocalEditorBones.current),
                help = montageText("mask_hint")
            ) { value ->
                edit {
                    it.copy(
                        mask = BoneMask.of(
                            *value.split(',').map(String::trim).filter(String::isNotEmpty).toTypedArray()
                        )
                    )
                }
            }
        }
        InspectorButton(montageText("remove_clip"), tags = listOf("danger")) {
            document.edit(label = UndoLabel(montageKey("remove_clip"))) { montage -> montage.copy(clips = montage.clips.filterNot { it.id == id }) }
            state.pick = null
        }
    }
}

/** The fields of an attachment the montage animates, and the stretch of time the montage hangs it for. */
private class MontageAttachmentAnimation(private val state: MontageEditorState) : AttachmentAnimation {
    override fun fields(bone: String?, attachment: RigAttachmentSpec): AnimatedFields {
        val names = RigFields.animatable(attachment).mapTo(HashSet()) { it.name }
        return object : AnimatedFields {
            override fun isAnimated(name: String): Boolean? =
                if (name in names) state.session.isAnimated(bone, attachment.id, name) else null

            override fun toggle(name: String) = state.session.toggle(bone, attachment.id, name)
        }
    }

    @Composable
    override fun Header(bone: String?, attachment: RigAttachmentSpec) {
        val document = state.rigs
        if (!document.owns(bone, attachment.id)) return FieldLabel(
            montageText("model_attachment"),
            montageText("model_attachment_hint")
        )
        val owned =
            state.document.montage.attachments.firstOrNull { it.bone == bone && it.spec.id == attachment.id } ?: return
        val duration = state.document.montage.duration
        val session = state.session
        FieldLabel(montageText("windows"), montageText("windows_hint"))
        owned.windows.forEachIndexed { index, window ->
            key(index) {
                Row(tags = listOf("insp-input-row")) {
                    NumberRow(montageText("window_start"), window.start, id = "window-$index-start", min = 0f) { value ->
                        session.editWindow(bone, attachment.id, index) { it.copy(start = value) }
                    }
                    NumberRow(
                        montageText("window_end"),
                        window.end ?: duration,
                        id = "window-$index-end",
                        min = 0f
                    ) { value ->
                        session.editWindow(
                            bone,
                            attachment.id,
                            index
                        ) { it.copy(end = value.takeIf { end -> end < duration }) }
                    }
                }
            }
        }
        InspectorButton(montageText("add_window")) {
            session.addWindow(
                bone,
                attachment.id,
                session.timeline.currentTime
            )
        }
    }
}

private fun BoneMask.text(): String = (includes + excludes.map { "!$it" }).joinToString(", ")
