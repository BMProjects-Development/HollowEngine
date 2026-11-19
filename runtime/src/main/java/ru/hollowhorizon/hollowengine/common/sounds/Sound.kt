package ru.hollowhorizon.hollowengine.common.sounds

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.api.Syncable
import ru.hollowhorizon.hollowengine.common.attachments.editor.*
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.scripting.annotations.Attach

private const val LANG = "hollowengine.gui.sound_attachment"

/**
 * A sound that plays where it hangs: at a point of a bone, or through [areas] around it, as music that fills a
 * house and fades out past its walls.
 */
@Serializable
@Registerable
@Syncable
@SerialName("hollowengine:rig/sound")
@EditorName("hollowengine.gui.rig_editor.kind_sound")
@EditorIcon("hollowengine:textures/gui/icons/vfx/node_sound.svg")
@Attach(SceneObject::class)
data class Sound(
    @EditorHidden val id: String = "sound",
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
)

