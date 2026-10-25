package ru.hollowhorizon.hollowengine.client.ui.ide.files.sounds

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.sounds.ClientSounds
import ru.hollowhorizon.hollowengine.client.sounds.ManagedSound
import ru.hollowhorizon.hollowengine.client.sounds.SoundDecoders
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.AnimatorColors
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorIconButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorStylesheet
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.sounds.MediaSource
import ru.hollowhorizon.hollowengine.common.sounds.SoundMode
import ru.hollowhorizon.hollowengine.common.sounds.SoundPlayback
import ru.hollowhorizon.hollowengine.common.sounds.SoundRepeat
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * An audio file opened in the IDE: its waveform, to listen to from anywhere in it. A press on the waveform goes there.
 */
@Composable
internal fun AudioFilePanel(path: String) {
    val state = remember(path) { AudioFileState(path) }

    LaunchedEffect(state) { state.load() }
    LaunchedEffect(state) {
        while (isActive) withFrameNanos { state.follow() }
    }
    DisposableEffect(state) { onDispose { state.stop() } }

    Column(
        tags = listOf("ide-file-panel"),
        modifier = Modifier.style(InspectorStylesheet).size(100.percent, 100.percent).background(AnimatorColors.Canvas)
            .padding(8.px).gap(8.px),
    ) {
        Row(modifier = Modifier.size(100.percent).gap(4.px).alignItems(vertical = UiAlign.CENTER)) {
            InspectorIconButton(
                if (state.playing) PAUSE_ICON else PLAY_ICON,
                text(if (state.playing) "pause" else "play"),
            ) { if (state.playing) state.pause() else state.play() }
            InspectorIconButton(STOP_ICON, text("stop")) { state.stop() }
            InspectorIconButton(LOOP_ICON, text("loop"), active = state.loop) { state.loop = !state.loop }
            Text(
                "${clock(state.cursor)} / ${clock(state.duration)}",
                modifier = Modifier.fontSize(10f).foreground(AnimatorColors.Text),
            )
            Box(modifier = Modifier.grow(1f))
            Text(state.info, modifier = Modifier.fontSize(9f).foreground(AnimatorColors.Muted))
        }
        Waveform(state)
    }
}

@Composable
private fun Waveform(state: AudioFileState) {
    val peaks = state.peaks
    Box(
        id = "audio-waveform",
        modifier = Modifier.size(100.percent, 0.px).grow(1f).background(AnimatorColors.Panel)
            .input(hoverable = true, clickable = true, draggable = true)
            .onPress { event -> state.seekAt(event.localX / state.width) }
            .onDrag { event -> state.seekAt(event.localX / state.width) }
            .onPlaced { rect -> state.width = rect.width.coerceAtLeast(1f) }
            .drawBehind(key = listOf(peaks, state.cursor, state.duration)) {
                val bounds = bounds
                val middle = bounds.height / 2f
                drawRect(UiRect(0f, middle, bounds.width, 1f), UiPaint.Color(AnimatorColors.Grid))
                if (peaks != null && peaks.isNotEmpty()) {
                    val columns = bounds.width.toInt().coerceAtLeast(1)
                    for (column in 0 until columns) {
                        val from = column * peaks.size / columns
                        val to = max(from + 1, (column + 1) * peaks.size / columns)
                        var peak = 0f
                        for (index in from until to.coerceAtMost(peaks.size)) peak = max(peak, peaks[index])
                        val height = (peak * (bounds.height - 4f)).coerceAtLeast(1f)
                        drawRect(UiRect(column.toFloat(), middle - height / 2f, 1f, height), UiPaint.Color(WAVE_COLOR))
                    }
                }
                if (state.duration > 0.0) {
                    val x = (state.cursor / state.duration * bounds.width).toFloat()
                    drawRect(UiRect(x - 0.5f, 0f, 1.5f, bounds.height), UiPaint.Color(AnimatorColors.NodeSelected))
                }
            },
    ) {
        state.error?.let { Text(it, modifier = Modifier.fontSize(10f).foreground(AnimatorColors.Muted).padding(8.px)) }
    }
}

/** What the panel of one audio file knows: its waveform, and where in it playback is. */
@Stable
private class AudioFileState(path: String) {
    private val source = audioSourceOf(path)
    private val id = "hollowengine:ide/audio/$path"

    /** The loudest sample of every [BLOCK] frames, from 0 to 1. */
    var peaks by mutableStateOf<FloatArray?>(null)
        private set
    var duration by mutableStateOf(0.0)
        private set
    var info by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Seconds into the file where playback is, or starts from. */
    var cursor by mutableStateOf(0.0)
        private set
    var playing by mutableStateOf(false)
        private set
    var loop by mutableStateOf(false)
    var width = 1f

    @Volatile
    private var sound: ManagedSound? = null

    suspend fun load() {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                SoundDecoders.open(source, Minecraft.getInstance().resourceManager).use { stream ->
                    val format = stream.format
                    val channels = format.channels
                    val blocks = ArrayList<Float>()
                    var peak = 0
                    var inBlock = 0
                    var frames = 0L
                    while (true) {
                        val chunk = stream.read(CHUNK).order(ByteOrder.LITTLE_ENDIAN)
                        if (!chunk.hasRemaining()) break
                        while (chunk.remaining() >= channels * 2) {
                            repeat(channels) { peak = max(peak, abs(chunk.short.toInt())) }
                            frames++
                            if (++inBlock == BLOCK) {
                                blocks += peak / 32768f
                                peak = 0
                                inBlock = 0
                            }
                        }
                    }
                    if (inBlock > 0) blocks += peak / 32768f
                    Triple(blocks.toFloatArray(), frames / format.sampleRate.toDouble(), format)
                }
            }
        }
        result.onSuccess { (blocks, seconds, format) ->
            peaks = blocks
            duration = seconds
            info = "${format.sampleRate.toInt()} Hz | ${if (format.channels == 1) text("mono") else text("stereo")}"
        }.onFailure { error = text("unreadable").format(it.message ?: it.javaClass.simpleName) }
    }

    /** Moves the cursor along with what plays, once a frame. */
    fun follow() {
        val playingNow = sound?.takeIf { !it.isStopped }
        if (playingNow == null) {
            if (playing) {
                playing = false
                if (!loop) cursor = 0.0
            }
            return
        }
        val position = playingNow.position
        if (!loop && duration > 0.0 && position >= duration) return stop()
        cursor = if (duration > 0.0) position % duration else position
        playing = !playingNow.isPaused
    }

    fun play() {
        val existing = sound?.takeIf { !it.isStopped }
        val from = cursor
        val settings =
            SoundPlayback(repeat = if (loop) SoundRepeat.Forever else SoundRepeat.Once, mode = SoundMode.GLOBAL)
        playing = true
        Minecraft.getInstance().execute {
            if (existing != null && existing.settings.repeat == settings.repeat) existing.resume()
            else sound = ClientSounds.play(source, settings, id = id, start = from, pauseWithGame = false)
        }
    }

    fun pause() {
        playing = false
        Minecraft.getInstance().execute { sound?.pause() }
    }

    fun stop() {
        playing = false
        cursor = 0.0
        Minecraft.getInstance().execute {
            ClientSounds.stop(id, 0f)
            sound = null
        }
    }

    /** Goes to [fraction] of the way through the file. */
    fun seekAt(fraction: Float) {
        if (duration <= 0.0) return
        val seconds = fraction.coerceIn(0f, 1f) * duration
        cursor = seconds
        val playingNow = sound?.takeIf { !it.isStopped && !it.isPaused } ?: run {
            Minecraft.getInstance().execute { ClientSounds.stop(id, 0f); sound = null }
            return
        }
        Minecraft.getInstance().execute {
            sound = ClientSounds.play(source, playingNow.settings, id = id, start = seconds, pauseWithGame = false)
        }
    }
}

private fun clock(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0)
    val minutes = (total / 60).toInt()
    return "%d:%04.1f".format(minutes, total - minutes * 60)
}

private fun text(key: String) = "hollowengine.gui.audio_preview.$key".lang

private val WAVE_COLOR = UiColor(0.44f, 0.74f, 0.85f)
private const val BLOCK = 256
private const val CHUNK = 65_536
private const val PLAY_ICON = "hollowengine:textures/gui/icons/play.svg"
private const val PAUSE_ICON = "hollowengine:textures/gui/icons/pause.svg"
private const val STOP_ICON = "hollowengine:textures/gui/icons/stop.svg"
private const val LOOP_ICON = "hollowengine:textures/gui/icons/loops.svg"

/** What the IDE opens as audio. Video containers, though they may carry sound, open as video. */
internal val AudioFileExtensions =
    listOf(".ogg", ".wav", ".mp3", ".flac", ".opus", ".aac", ".m4a", ".aif", ".aiff", ".wma", ".mka")

/** The sound an IDE path names: a resource of the loaded packs opened from the asset manager, or a file of the project. */
internal fun audioSourceOf(path: String): MediaSource {
    if (!path.startsWith(PACK_PREFIX)) return MediaSource.File(path)
    val resource = path.removePrefix(PACK_PREFIX)
    return MediaSource.Resource(
        ResourceLocation.fromNamespaceAndPath(
            resource.substringBefore('/'),
            resource.substringAfter('/')
        )
    )
}

private const val PACK_PREFIX = "resource://client/assets/"
