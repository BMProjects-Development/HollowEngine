package ru.hollowhorizon.hollowengine.common.sounds

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentType

@Serializable
@SerialName("hollowengine:rig/sound")
data class SoundBoneAttachmentSpec(
    @EditorHidden override val id: String = "sound",
    @EditorName("hollowengine.gui.sound_attachment.source") val source: String = "",
    @EditorName("hollowengine.gui.sound_attachment.auto_play") val autoPlay: Boolean = true,
    @EditorName("hollowengine.gui.sound_attachment.playback") val playback: SoundPlayback = SoundPlayback(),
) : RigAttachmentSpec() {
    override fun withId(id: String) = copy(id = id)
    override fun structure() = SoundBoneAttachmentSpec(id)

    companion object {
        val TYPE = RigAttachmentType(
            "hollowengine:rig/sound", SoundBoneAttachmentSpec::class, serializer(),
            "hollowengine.gui.rig_editor.kind_sound", { SoundBoneAttachmentSpec(id = it) },
            allowedOnModel = true, namedAcrossRig = true, editorColor = 0x70BCD8,
        )
    }
}
