package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import com.mojang.blaze3d.systems.RenderSystem
import ru.hollowhorizon.hollowengine.api.VideoApi
import ru.hollowhorizon.hollowengine.api.VideoPlaybackOptions
import ru.hollowhorizon.hollowengine.api.VideoPlayer
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.UiGradientStop
import ru.hollowhorizon.hollowengine.client.ui.style.UiImageFit
import ru.hollowhorizon.hollowengine.client.ui.style.UiShadow
import ru.hollowhorizon.hollowengine.client.utils.lang
import kotlin.math.abs

/**
 * Inline video playback. Decoding is provided by the video addon through [VideoApi]; when the addon
 * is not installed the widget renders a "no video addon" placeholder instead of playing.
 *
 * The player session lives for as long as the composable stays in the composition and is released
 * through a [DisposableEffect]. With [controls] enabled, hovering the video shows a control bar:
 * play/pause, elapsed/total time, a seekable timeline and a volume popup that opens on hover and
 * closes when the pointer leaves its bounds.
 */
@Composable
fun Video(
    source: String,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    controls: Boolean = true,
    autoPlay: Boolean = true,
    volume: Float = 1f,
    startSeconds: Double = 0.0,
    fit: UiImageFit = UiImageFit.CONTAIN,
    onEnded: (() -> Unit)? = null,
) {
    val api = remember { VideoApi.find() }
    if (api == null) {
        VideoPlaceholder(id, tags, modifier) {
            Text("hollowengine.gui.video.missing_addon".lang, tags = listOf("video-message"))
        }
        return
    }

    var player by remember(source) { mutableStateOf<VideoPlayer?>(null) }
    val state = remember(source) { VideoWidgetState() }
    val endedCallback = rememberUpdatedState(onEnded)

    DisposableEffect(source) {
        val created = api.createPlayer(
            source,
            VideoPlaybackOptions(
                closeOnEnd = false,
                volume = volume,
                startSeconds = startSeconds,
                autoPlay = autoPlay,
            ),
        )

        created?.setFrameListener {
            state.texture = created.texture?.toString()
            state.positionSeconds = created.positionSeconds
            state.durationSeconds = created.durationSeconds
            state.playing = created.playing
            state.failed = created.error != null
            if (created.ended && !state.ended) {
                state.ended = true
                endedCallback.value?.invoke()
            } else if (!created.ended && state.ended) {
                state.ended = false
            }
        }
        player = created
        onDispose {
            RenderSystem.recordRenderCall {
                created?.setFrameListener(null)
                created?.close()
            }
        }
    }

    var bounds by remember { mutableStateOf(UiRect.Zero) }
    val activePlayer = player

    val frameClickModifier = if (controls && activePlayer != null) {
        Modifier.input(clickable = true).onClick { event ->
            if (activePlayer.playing) activePlayer.pause() else activePlayer.play()
            event.consume()
        }
    } else {
        Modifier
    }

    VideoPlaceholder(id, tags, modifier, Modifier.onPlaced { bounds = it }.then(frameClickModifier)) {
        val texture = state.texture
        if (texture != null) {
            Image(
                texture,
                tags = listOf("video-frame"),
                modifier = Modifier.size(100.percent, 100.percent).imageFit(fit),
            )
        }
        if (state.failed) {
            Text("hollowengine.gui.video.playback_failed".lang, tags = listOf("video-message"))
        }

        if (controls && !state.failed && activePlayer != null) {
            val pointer = LocalPointer.current
            val hovered = pointer.isKnown && bounds.contains(pointer.x, pointer.y)
            AnimatedVisibility(
                visible = hovered,
                modifier = Modifier.size(100.percent, UiLength.Auto).align(vertical = UiAlign.END),
                slideY = 8f,
            ) {
                VideoControlsBar(player = activePlayer, state = state)
            }
        }
    }
}

/** Shared black letterbox container for the video, the missing-addon stub and the failure state. */
@Composable
private fun VideoPlaceholder(
    id: String?,
    tags: Iterable<String>,
    modifier: Modifier?,
    extraModifier: Modifier = Modifier,
    content: HollowUiContent,
) {
    Box(
        id = id,
        mode = UiBoxMode.STACK,
        tags = tags + "video",
        modifier = Modifier.size(100.percent, 100.percent)
            .background(VideoBackgroundColor)
            .clip()
            .alignItems(UiAlign.CENTER, UiAlign.CENTER)
            .then(extraModifier)
            .then(modifier ?: Modifier),
        content = content,
    )
}

@Composable
private fun VideoControlsBar(
    player: VideoPlayer,
    state: VideoWidgetState,
) {
    Column(
        tags = listOf("video-controls"),
        modifier = Modifier.size(100.percent, UiLength.Auto)
            .background(ScrimAngleDegrees, ScrimStops)
            .padding(10.px, 6.px)
            .gap(2.px)
            // Swallow clicks on the bar's empty areas so they don't reach the frame play/pause toggle.
            .input(clickable = true)
            .onClick { it.consume() },
    ) {
        VideoTimeline(player, state)
        Row(
            tags = listOf("video-controls-row"),
            modifier = Modifier.size(100.percent, UiLength.Auto)
                .alignItems(vertical = UiAlign.CENTER)
                .gap(10.px),
        ) {
            val playing = state.playing
            VideoIconButton(
                source = if (playing) PauseIcon else PlayIcon,
                tag = "video-controls-toggle",
            ) {
                if (playing) player.pause() else player.play()
            }
            VideoVolumeControl(player)
            Text(
                "${formatTime(state.positionSeconds)} / ${formatTime(state.durationSeconds)}",
                tags = listOf("video-controls-time"),
                modifier = Modifier.fontSize(9f).foreground(TimeTextColor),
            )
        }
    }
}

@Composable
private fun VideoIconButton(
    source: String,
    tag: String,
    extraModifier: Modifier = Modifier,
    onClick: (UiEvent) -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    Image(
        source,
        tags = listOf("video-icon-button", tag),
        modifier = Modifier.size(13.px, 13.px)
            .cursor(UiCursorShape.HAND)
            .opacity(if (hovered) 1f else 0.78f)
            .onEnter { hovered = true }
            .onExit { hovered = false }
            .onClick { event ->
                onClick(event)
                event.consume()
            }
            .then(extraModifier),
    )
}

@Composable
private fun VideoTimeline(player: VideoPlayer, state: VideoWidgetState) {
    var seekPreview by remember { mutableStateOf<Double?>(null) }
    // nanoTime of the last live seek dispatched during a scrub, for throttling the decoder.
    val lastSeekNanos = remember { longArrayOf(0L) }
    val duration = state.durationSeconds
    val position = state.positionSeconds
    seekPreview?.let { preview ->
        if (abs(position - preview) < SeekPreviewReleaseSeconds) seekPreview = null
    }
    val shown = seekPreview ?: position
    val fraction = if (duration > 0.0) (shown / duration).toFloat() else 0f
    VideoBarSlider(
        fraction = fraction,
        tag = "video-timeline",
        // Seek live while scrubbing (throttled) so the picture tracks the cursor instead of only
        // jumping on release. The press that starts a scrub also seeks immediately, so a single
        // click on the timeline is instant.
        onScrub = { fraction ->
            if (duration > 0.0) {
                val target = fraction * duration
                seekPreview = target
                val now = System.nanoTime()
                if (now - lastSeekNanos[0] >= LiveSeekIntervalNanos) {
                    lastSeekNanos[0] = now
                    player.seek(target)
                }
            }
        },
        onCommit = { fraction ->
            if (duration > 0.0) {
                val target = fraction * duration
                player.seek(target)
                seekPreview = target
                lastSeekNanos[0] = System.nanoTime()
            }
        },
    )
}

@Composable
private fun VideoBarSlider(
    fraction: Float,
    tag: String,
    modifier: Modifier? = null,
    onScrub: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    var bounds by remember { mutableStateOf(UiRect.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var lastScrubbed by remember { mutableStateOf(0f) }
    val pointer = LocalPointer.current
    val hovered = pointer.isKnown && bounds.contains(pointer.x, pointer.y)
    val active = hovered || dragging
    val clamped = fraction.coerceIn(0f, 1f)

    fun fractionAt(event: UiEvent): Float = (event.localX / event.width.coerceAtLeast(1f)).coerceIn(0f, 1f)

    Box(
        mode = UiBoxMode.STACK,
        tags = listOf("video-slider", tag),
        modifier = Modifier.size(100.percent, 12.px)
            .cursor(UiCursorShape.HAND)
            .onPlaced { bounds = it }
            .onPress { event ->
                dragging = true
                lastScrubbed = fractionAt(event)
                onScrub(lastScrubbed)
            }
            .onDrag { event ->
                lastScrubbed = fractionAt(event)
                onScrub(lastScrubbed)
            }
            .onRelease {
                if (dragging) onCommit(lastScrubbed)
                dragging = false
            }
            .then(modifier ?: Modifier),
    ) {
        val trackHeight = if (active) 4.px else 2.px
        Box(
            tags = listOf("video-slider-track"),
            modifier = Modifier.size(100.percent, trackHeight)
                .align(vertical = UiAlign.CENTER)
                .background(SliderTrackColor)
                .borderRadius(2f),
        )
        Box(
            tags = listOf("video-slider-fill"),
            modifier = Modifier.size((clamped * 100f).percent, trackHeight)
                .align(vertical = UiAlign.CENTER)
                .background(AccentColor)
                .borderRadius(2f),
        )
        if (active) {
            Box(
                tags = listOf("video-slider-thumb"),
                modifier = Modifier.size(9.px, 9.px)
                    .align(vertical = UiAlign.CENTER)
                    .position((clamped * 100f).percent - 4.px, 0.px)
                    .background(ThumbColor)
                    .borderRadius(4.5f)
                    .shadow(UiShadow(offset = UiVec3(0f, 1f, 0f), blur = 1.2f, color = UiColor(0f, 0f, 0f, 0.5f))),
            )
        }
    }
}

@Composable
private fun VideoVolumeControl(player: VideoPlayer) {
    var groupBounds by remember { mutableStateOf(UiRect.Zero) }
    var level by remember { mutableStateOf(player.volume) }
    var dragging by remember { mutableStateOf(false) }
    // Volume before the last mute, restored when the icon is clicked again.
    val lastAudibleLevel = remember { floatArrayOf(if (player.volume > 0f) player.volume else 1f) }

    val pointer = LocalPointer.current
    // The slider expands out of the icon while the pointer is over the icon+slider group, and stays
    // open while a drag is in progress even if the pointer wanders off — no click needed, no popup.
    val hovered = pointer.isKnown && groupBounds.contains(pointer.x, pointer.y)
    val expanded = hovered || dragging
    val reveal by animateFloatAsState(if (expanded) 1f else 0f, durationMillis = 150L)
    val revealWidth = reveal * VolumeSliderWidth

    Row(
        tags = listOf("video-volume"),
        modifier = Modifier.size(UiLength.Auto, 12.px)
            .alignItems(vertical = UiAlign.CENTER)
            .gap(if (revealWidth > 0.5f) 4.px else 0.px)
            .input(hoverable = true)
            .onPlaced { groupBounds = it },
    ) {
        VideoIconButton(
            source = VolumeIcon,
            tag = "video-controls-volume",
            extraModifier = Modifier.opacity(if (level > 0f) 1f else 0.5f),
        ) {
            if (level > 0f) {
                lastAudibleLevel[0] = level
                level = 0f
            } else {
                level = lastAudibleLevel[0]
            }
            player.setVolume(level)
        }
        // Fixed-width slider clipped by an animated-width box so the track wipes in instead of
        // squashing; the slider is inset (padding) so the thumb never touches — and gets cut by — the
        // clip edge. Kept mounted while revealed or dragging.
        Box(modifier = Modifier.size(revealWidth.px, 14.px).clip()) {
            if (revealWidth > 0.5f) {
                VideoBarSlider(
                    fraction = level,
                    tag = "video-volume-slider",
                    modifier = Modifier.size(VolumeSliderWidth.px, 14.px)
                        .padding(VolumeThumbInset.px, 0.px)
                        .align(vertical = UiAlign.CENTER),
                    onScrub = {
                        dragging = true
                        level = it
                        if (it > 0f) lastAudibleLevel[0] = it
                        player.setVolume(it)
                    },
                    onCommit = {
                        dragging = false
                        level = it
                        if (it > 0f) lastAudibleLevel[0] = it
                        player.setVolume(it)
                    },
                )
            }
        }
    }
}

@Stable
private class VideoWidgetState {
    var texture by mutableStateOf<String?>(null)
    var positionSeconds by mutableStateOf(0.0)
    var durationSeconds by mutableStateOf(0.0)
    var playing by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var failed by mutableStateOf(false)
}

private fun formatTime(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val secs = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

private val VideoBackgroundColor = UiColor(0f, 0f, 0f, 1f)

// Neutral, player-agnostic accent (was warm orange) so the widget doesn't read as any one site's brand.
private val AccentColor = UiColor(1f, 1f, 1f, 0.92f)
private val SliderTrackColor = UiColor(1f, 1f, 1f, 0.25f)
private val ThumbColor = UiColor(1f, 1f, 1f, 1f)
private val TimeTextColor = UiColor(1f, 1f, 1f, 0.85f)

private const val ScrimAngleDegrees = 180f
private val ScrimStops = listOf(
    UiGradientStop(0f, UiColor(0f, 0f, 0f, 0f)),
    UiGradientStop(1f, UiColor(0f, 0f, 0f, 0.8f)),
)

private const val PlayIcon = "hollowengine:textures/gui/icons/play.svg"
private const val PauseIcon = "hollowengine:textures/gui/icons/pause.svg"
private const val VolumeIcon = "hollowengine:textures/gui/icons/volume.svg"
private const val SeekPreviewReleaseSeconds = 0.12
private const val VolumeSliderWidth = 68f
private const val VolumeThumbInset = 6f
private const val LiveSeekIntervalNanos = 40_000_000L
