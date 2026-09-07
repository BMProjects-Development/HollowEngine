package ru.hollowhorizon.hollowengine.addons.video.events

import ru.hollowhorizon.hollowengine.common.events.ClientEvent
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
sealed class VideoPlaybackEvent(
    val source: String,
) : ClientEvent {
    class Started(source: String) : VideoPlaybackEvent(source) {
        companion object : EventHandler<Started>()
    }

    class Finished(source: String) : VideoPlaybackEvent(source) {
        companion object : EventHandler<Finished>()
    }

    class Failed(source: String, val error: Throwable) : VideoPlaybackEvent(source) {
        companion object : EventHandler<Failed>()
    }
}
