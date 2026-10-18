package ru.hollowhorizon.hollowengine.client.sounds

import ru.hollowhorizon.hollowengine.common.sounds.SoundSource

object UiSounds {
    fun connect() = ClientSounds.play(SoundSource.parse("hollowengine:ui/connect.wav"))
    fun select() = ClientSounds.play(SoundSource.parse("hollowengine:ui/select.wav"))
}
