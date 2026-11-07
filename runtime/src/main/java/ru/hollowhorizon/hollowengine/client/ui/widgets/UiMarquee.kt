package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import kotlinx.coroutines.isActive
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.UiTransition

/**
 * A line of [lead] and [tail] that does not fit scrolled to its end and back while [active], with the cut edges faded.
 * The tail sits at the end of the line while both fit, and right after the lead when they do not.
 */
@Composable
fun Marquee(
    active: Boolean,
    modifier: Modifier = Modifier,
    tags: List<String> = emptyList(),
    gap: Float = MarqueeGap,
    tail: (@Composable () -> Unit)? = null,
    lead: @Composable () -> Unit,
) {
    var viewport by remember { mutableStateOf(0f) }
    var leadWidth by remember { mutableStateOf(0f) }
    var tailWidth by remember { mutableStateOf(0f) }
    val inlineTail = tail != null && !marqueeTailFitsApart(viewport, leadWidth, tailWidth, gap)
    val overflow = (leadWidth + (if (inlineTail) gap + tailWidth else 0f) - viewport).coerceAtLeast(0f)
    val moving = active && overflow > 0.5f
    val travelMs = (overflow * MsPerPixel).toLong().coerceIn(MinTravelMs, MaxTravelMs)
    var atEnd by remember { mutableStateOf(false) }
    LaunchedEffect(moving, travelMs) {
        atEnd = false
        if (!moving) return@LaunchedEffect
        val cycleMs = 2L * PauseMs + 2L * travelMs
        var baseNanos = -1L
        while (isActive) {
            withFrameNanos { now ->
                if (baseNanos < 0L) baseNanos = now
                val elapsed = (now - baseNanos) / 1_000_000L % cycleMs
                atEnd = elapsed in PauseMs until PauseMs + travelMs + PauseMs
            }
        }
    }
    Box(tags = listOf("marquee") + tags, modifier = modifier.clip().onPlaced { viewport = it.width }) {
        Row(
            tags = listOf("marquee-text"),
            modifier = Modifier.gap(gap.px).translate(x = if (atEnd) -overflow else 0f)
                .transition(UiTransition("translate", durationMillis = travelMs)).clip(false),
        ) {
            Box(modifier = Modifier.onPlaced { leadWidth = it.width }.clip(false)) { lead() }
            if (inlineTail) Box(modifier = Modifier.onPlaced { tailWidth = it.width }) { tail() }
        }
        if (tail != null && !inlineTail) {
            Box(modifier = Modifier.align(UiAlign.END, UiAlign.CENTER).onPlaced { tailWidth = it.width }) { tail() }
        }
        if (overflow > 0.5f) {
            Fade("end", UiAlign.END, FadeOvershoot, shown = !atEnd)
            Fade("start", UiAlign.START, -FadeOvershoot, shown = atEnd)
        }
    }
}

@Composable
private fun Fade(side: String, align: UiAlign, shift: Float, shown: Boolean) {
    Box(
        tags = listOf("marquee-fade", side),
        modifier = Modifier.size(FadeWidth.px, 100.percent).align(align, UiAlign.CENTER).position(shift.px, 0.px)
            .opacity(if (shown) 1f else 0f).transition(UiTransition("opacity", durationMillis = FadeDurationMillis)),
    )
}

/** Whether a tail [tailWidth] wide fits at the end of a line [viewportWidth] wide after a lead [leadWidth] wide. */
internal fun marqueeTailFitsApart(
    viewportWidth: Float,
    leadWidth: Float,
    tailWidth: Float,
    gap: Float = MarqueeGap,
): Boolean {
    if (viewportWidth <= 0f || tailWidth <= 0f) return true
    return leadWidth + gap + tailWidth <= viewportWidth + 0.5f
}

private const val MarqueeGap = 6f
private const val PauseMs = 900L
private const val MsPerPixel = 16f
private const val MinTravelMs = 700L
private const val MaxTravelMs = 6000L
private const val FadeWidth = 16f
private const val FadeDurationMillis = 300L
private const val FadeOvershoot = 2f
