package ru.hollowhorizon.hollowengine.common.sounds

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.*
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentType

private const val LANG = "hollowengine.gui.sound_attachment"

/**
 * A sound that plays where it hangs: at a point of a bone, or through [areas] around it, as music that fills a
 * house and fades out past its walls.
 */
@Serializable
@SerialName("hollowengine:rig/sound")
data class SoundBoneAttachmentSpec(
    @EditorHidden override val id: String = "sound",
    @EditorName("$LANG.source")
    @EditorWidget(EditorWidgets.SOUND_SOURCE)
    @EditorNotAnimated
    val source: String = "",
    @EditorName("$LANG.auto_play")
    @EditorNotAnimated
    val autoPlay: Boolean = true,
    @EditorName("$LANG.playback")
    val playback: SoundPlayback = SoundPlayback(),
    @EditorName("$LANG.areas")
    @EditorDescription("$LANG.areas.hint")
    val areas: List<SoundArea> = emptyList(),
    @EditorHidden
    @EditorNotAnimated
    val clock: Float? = null,
    @EditorHidden
    @EditorNotAnimated
    val playing: Boolean = true,
) : RigAttachmentSpec() {
    override fun withId(id: String) = copy(id = id)

    override fun structure() = SoundBoneAttachmentSpec(id)

    override fun held() = copy(playing = false, clock = null)

    override fun inStretch(seconds: Float) = copy(clock = seconds)

    companion object {
        val TYPE = RigAttachmentType(
            "hollowengine:rig/sound", SoundBoneAttachmentSpec::class, serializer(),
            "hollowengine.gui.rig_editor.kind_sound", { SoundBoneAttachmentSpec(id = it) },
            allowedOnModel = true, namedAcrossRig = true, editorColor = 0x70BCD8,
        )
    }
}
