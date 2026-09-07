package ru.hollowhorizon.hollowengine.addons.video.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.addons.video.playback.VideoPlayerSession
import ru.hollowhorizon.hollowengine.addons.video.screen.HollowVideoScreen
import ru.hollowhorizon.hollowengine.api.VideoApi
import ru.hollowhorizon.hollowengine.api.VideoPlaybackOptions
import ru.hollowhorizon.hollowengine.api.VideoPlayer
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderTickEvent
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class HollowVideo(private val addonScope: CoroutineScope) : VideoApi, AutoCloseable {
    private val sessions = CopyOnWriteArrayList<VideoPlayerSession>()
    private var activeScreen: HollowVideoScreen? = null

    init {
        RenderTickEvent.Pre.subscribe(addonScope) {
            sessions.forEach(VideoPlayerSession::renderTick)
        }
    }

    override fun play(path: Path, options: VideoPlaybackOptions) {
        play(VideoSourceResolver.resolve(path), options)
    }

    override fun play(source: String, options: VideoPlaybackOptions) {
        val session = openSession(source, options)
        Minecraft.getInstance().execute {
            activeScreen = HollowVideoScreen(session, options) {
                activeScreen = null
            }.also(Minecraft.getInstance()::setScreen)
        }
    }

    override fun createPlayer(path: Path, options: VideoPlaybackOptions): VideoPlayer =
        openSession(VideoSourceResolver.resolve(path), options)

    override fun createPlayer(source: String, options: VideoPlaybackOptions): VideoPlayer =
        openSession(source, options)

    private fun openSession(source: String, options: VideoPlaybackOptions): VideoPlayerSession {
        check(addonScope.isActive) { "The video addon is not active" }
        val session = VideoPlayerSession(
            source = VideoSourceResolver.resolve(source),
            options = options,
            parentScope = addonScope,
            onClosed = sessions::remove,
        )
        sessions += session
        return session
    }

    override fun close() {
        val minecraft = Minecraft.getInstance()
        minecraft.execute {
            val screen = activeScreen
            if (screen != null && minecraft.screen === screen) minecraft.setScreen(null)
            activeScreen = null
            sessions.toList().forEach(VideoPlayerSession::close)
        }
    }
}
