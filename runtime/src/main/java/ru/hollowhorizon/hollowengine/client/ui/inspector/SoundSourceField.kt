package ru.hollowhorizon.hollowengine.client.ui.inspector

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.client.sounds.ClientSounds
import ru.hollowhorizon.hollowengine.client.sounds.ManagedSound
import ru.hollowhorizon.hollowengine.client.sounds.SoundDecoders
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.sounds.MediaSource
import ru.hollowhorizon.hollowengine.common.sounds.SoundMode
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource

/**
 * A sound typed by name, offered among the sound events and the sound files there are, checked against them, and
 * listened to with the button beside it as [playback] would play it, around the listener.
 */
@Composable
fun SoundSourceField(
    label: String?,
    description: String?,
    value: String,
    id: String,
    playback: SoundPlayback = SoundPlayback(),
    onChange: (String) -> Unit,
) {
    Column(tags = listOf("insp-field")) {
        if (label != null) FieldLabel(label, description)
        Row(tags = listOf("insp-input-row")) {
            Column(modifier = Modifier.size(0.px, UiLength.Fit).grow(1f)) {
                AssetPathField(
                    value = value,
                    label = label.orEmpty(),
                    id = id,
                    candidates = ::soundCandidates,
                    exists = ::soundExists,
                    onChange = onChange,
                )
            }
            SoundAuditionButton(value, playback)
        }
    }
}

/** Plays [value] as [playback] would, around the listener, and stops it on a second press. */
@Composable
fun SoundAuditionButton(value: String, playback: SoundPlayback = SoundPlayback()) {
    var playing by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf<ManagedSound?>(null) }
    LaunchedEffect(playing) {
        while (playing) {
            delay(POLL_MILLIS)
            if (sound?.isStopped != false) playing = false
        }
    }
    val source = remember(value) { runCatching { SoundSource.parse(value) }.getOrNull() }
    InspectorIconButton(
        if (playing) STOP_ICON else PLAY_ICON,
        if (playing) "hollowengine.gui.inspector.sound.stop".lang else "hollowengine.gui.inspector.sound.listen".lang,
        active = playing,
    ) {
        if (playing) {
            Minecraft.getInstance().execute { ClientSounds.stop(AUDITION, 0f) }
            playing = false
        } else if (source != null) {
            sound = null
            playing = true
            Minecraft.getInstance().execute {
                sound = ClientSounds.play(source, playback.copy(mode = SoundMode.GLOBAL), id = AUDITION, pauseWithGame = false)
            }
        }
    }
}

/** The sound events of the loaded packs, and the sound files the decoders read. */
internal fun soundCandidates(host: InspectorHost): List<String> {
    val events = Minecraft.getInstance().soundManager.availableSounds.map { it.toString() }
    val files = host.assets((SoundSource.fileExtensions + SoundDecoders.extensions).map { ".$it" })
    return (events + files).distinct().sorted()
}

/** Whether [value] names a sound event that is registered or a sound file that is there. */
internal fun soundExists(value: String): Boolean {
    val source = runCatching { SoundSource.parse(value) }.getOrNull() ?: return false
    return when (source) {
        is SoundSource.Event -> Minecraft.getInstance().soundManager.getSoundEvent(source.id) != null
        is MediaSource.Resource -> resourceExists(source.id.toString())
        else -> true
    }
}

private const val AUDITION = "hollowengine:inspector/audition"
private const val PLAY_ICON = "hollowengine:textures/gui/icons/play.svg"
private const val STOP_ICON = "hollowengine:textures/gui/icons/stop.svg"
private const val POLL_MILLIS = 200L
