package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonObject
import ru.hollowhorizon.hollowengine.client.ui.inspector.AutoFields
import ru.hollowhorizon.hollowengine.client.ui.inspector.ComponentJson
import ru.hollowhorizon.hollowengine.client.ui.inspector.SoundSourceField
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import ru.hollowhorizon.hollowengine.common.vfx.VfxSoundSpec

/** What a sound node plays and how loud, then how it plays, the same as any sound's. */
@Composable
internal fun SoundFields(document: VfxEditing, state: VfxInspectorState, sound: VfxSoundSpec) {
    Folding(state, "sound", vfxText("section_sound"), VfxIcons.SOUND) {
        SoundSourceField(
            vfxText("sound_source"),
            vfxText("sound_source_hint"),
            sound.source,
            "vfx-sound-${sound.id}",
            sound.playback
        ) {
            document.replace(sound.copy(source = it))
        }
        VfxValueRow(vfxText("sound_volume"), sound.volume, VfxProperty.SOUND_VOLUME, vfxText("sound_volume_hint")) {
            document.replace(sound.copy(volume = it))
        }
        VfxValueRow(vfxText("sound_pitch"), sound.pitch, VfxProperty.SOUND_PITCH, vfxText("sound_pitch_hint")) {
            document.replace(sound.copy(pitch = it))
        }
    }
    Folding(state, "sound_playback", vfxText("section_sound_playback"), VfxIcons.SOUND) {
        val format = ComponentJson.format
        val json = format.encodeToJsonElement(SoundPlayback.serializer(), sound.playback) as JsonObject
        AutoFields(null, SoundPlayback.serializer().descriptor, json, "/vfx-sound-${sound.id}") { edited ->
            runCatching { format.decodeFromJsonElement(SoundPlayback.serializer(), edited) }.getOrNull()
                ?.let { document.replace(sound.copy(playback = it)) }
        }
    }
}
