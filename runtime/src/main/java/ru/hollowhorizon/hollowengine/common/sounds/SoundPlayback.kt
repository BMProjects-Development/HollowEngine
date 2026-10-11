package ru.hollowhorizon.hollowengine.common.sounds

import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDefault
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRange
import net.minecraft.sounds.SoundSource as SoundCategory

private const val LANG = "hollowengine.gui.sound_playback"

@Serializable
sealed interface SoundRepeat {
    @Serializable
    @EditorName("$LANG.repeat.once")
    data object Once : SoundRepeat
    @Serializable
    @EditorName("$LANG.repeat.count")
    data class Count(
        @EditorName("$LANG.repeat.plays")
        @EditorRange(min = 1.0)
        val plays: Int = 1,
    ) : SoundRepeat {
        init { require(plays > 0) { "The number of plays must be positive" } }
    }
    @Serializable
    @EditorName("$LANG.repeat.forever")
    data object Forever : SoundRepeat

    fun hasNext(completed: Long): Boolean = when (this) {
        Once -> completed < 1
        is Count -> completed < plays
        Forever -> true
    }
}

@Serializable
data class SoundPlayback(
    @EditorName("$LANG.repeat")
    val repeat: SoundRepeat = SoundRepeat.Once,
    @EditorName("$LANG.fade_in")
    @EditorRange(min = 0.0)
    val fadeIn: Float = 0f,
    @EditorName("$LANG.fade_out")
    @EditorRange(min = 0.0)
    val fadeOut: Float = 0f,
    @EditorName("$LANG.fade_each_cycle")
    val fadeEachCycle: Boolean = false,
    @EditorName("$LANG.volume")
    @EditorRange(min = 0.0)
    val volume: Float = 1f,
    @EditorName("$LANG.pitch")
    val pitch: Float = 1f,
    @EditorName("$LANG.category")
    val category: SoundCategory = SoundCategory.MASTER,
    @EditorName("$LANG.range")
    @EditorDescription("$LANG.range.hint")
    @EditorDefault("16.0")
    val range: Float? = null,
) {
    init {
        require(fadeIn.isFinite() && fadeIn >= 0f && fadeOut.isFinite() && fadeOut >= 0f)
        require(volume.isFinite() && volume >= 0f)
        require(pitch.isFinite() && pitch > 0f)
        require(range == null || range.isFinite() && range > 0f)
    }
}
