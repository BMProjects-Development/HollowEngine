package ru.hollowhorizon.hollowengine.client.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollState
import ru.hollowhorizon.hollowengine.client.ui.style.CompiledHss
import kotlin.coroutines.CoroutineContext

class HollowUiSurface(
    theme: CompiledHss? = null,
    stylesheet: CompiledHss? = null,
    scrollState: UiScrollState = UiScrollState(),
    coroutineContext: CoroutineContext = Dispatchers.Unconfined,
) : AutoCloseable {
    private val profiler = UiProfiler()
    private val composition = HollowUiComposition(coroutineContext)
    val runtime = HollowUiRuntime(theme, stylesheet, scrollState, profiler = profiler)
    private var hasContent = false

    /**
     * Puts the tree in [UiState.CLOSING] while the host plays its exit animation; see
     * [HollowUiRuntime.isClosing].
     */
    var isClosing: Boolean
        get() = runtime.isClosing
        set(value) {
            runtime.isClosing = value
        }

    private var frameTimeNanos by mutableStateOf(0L)
    private var pointer by mutableStateOf(UiPointer.Unknown)
    private var viewport by mutableStateOf(UiRect.Zero)
    private val overlayManager = OverlayManager()

    /** How the host keeps the pointer inside a region; set before [setContent], null for a host that cannot. */
    var pointerWrap: UiPointerWrap? = null

    fun setContent(content: HollowUiContent): BoxNode {
        hasContent = true
        return composition.setContent {
            CompositionLocalProvider(
                LocalUiFrameTimeNanos provides frameTimeNanos,
                LocalPointer provides pointer,
                LocalOverlayManager provides overlayManager,
                LocalUiViewport provides viewport,
                LocalUiFocusRequester provides UiFocusRequester(runtime::requestFocus),
                LocalUiPointerWrap provides pointerWrap,
            ) {
                content()
                OverlayHost()
            }
        }
    }

    /**
     * Opt-in per-frame time. A composable that animates off [LocalUiFrameTimeNanos] recomposes only
     * when this advances, so callers tick it (e.g. a `rebuildEveryFrame` screen) rather than the
     * surface driving every window/HUD/IDE every frame.
     */
    fun advanceFrameTime(nowNanos: Long) {
        frameTimeNanos = nowNanos
    }

    fun frame(
        width: Float,
        height: Float,
        x: Float,
        y: Float,
        nowNanos: Long = 0L,
    ): HollowUiFrame {
        check(hasContent) { "UI content has not been set" }
        val profile = profiler.beginFrame()
        pointer = UiPointer(x, y)
        if (viewport.width != width || viewport.height != height) viewport = UiRect(0f, 0f, width, height)
        val nowMillis = nowNanos / NanosPerMillisecond
        runtime.prepareFrame(nowMillis)
        val root = composition.frameRoot(nowNanos, profile)
        return runtime.frame(root, width, height, x, y, nowMillis, profile)
    }

    override fun close() {
        composition.close()
    }

    private companion object {
        const val NanosPerMillisecond = 1_000_000L
    }
}
