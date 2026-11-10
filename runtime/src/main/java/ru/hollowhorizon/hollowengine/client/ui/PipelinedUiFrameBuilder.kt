package ru.hollowhorizon.hollowengine.client.ui

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Frame pipelining shared by every UI host (screens, the IDE overlay, future HUDs): after a frame
 * is presented, the next one - recomposition, style resolve and layout, all input-independent
 * built on a background thread while the game renders, and the host consumes it one frame later.
 *
 * Every host shares ONE build thread, so UI builds are globally serialized. That serialization is
 * what makes it safe to touch process-global build state off the render thread notably the
 * [ru.hollowhorizon.hollowengine.client.ui.text.UiTextLayouter] singleton's measurement caches (a
 * reused width probe plus access-ordered LRU maps, none individually thread-safe).
 *
 * Input still runs on the render thread: call [await] before dispatching so node-tree mutation never
 * overlaps a build, and so callbacks (which may touch thread-bound game state) stay on the main
 * thread. The 1-frame-late pointer/animation clock is the accepted trade for the parallelism.
 */
class PipelinedUiFrameBuilder {
    private var pending: CompletableFuture<HollowUiFrame>? = null
    private var pendingWidth = 0f
    private var pendingHeight = 0f

    /**
     * The pipelined frame when one is ready and was built for [width]x[height]; otherwise null so
     * the caller builds synchronously, there is no build in flight (first frame), or the viewport
     * changed under the one that is (its layout is stale, discard it).
     */
    fun take(width: Float, height: Float): HollowUiFrame? {
        val future = pending ?: return null
        pending = null
        val frame = future.join()
        return frame.takeIf { pendingWidth == width && pendingHeight == height }
    }

    /** Kicks off the next build on the shared UI build thread. [build] runs off the render thread. */
    fun schedule(width: Float, height: Float, build: () -> HollowUiFrame) {
        pendingWidth = width
        pendingHeight = height
        pending = CompletableFuture.supplyAsync(build, Executor)
    }

    /**
     * Blocks until the in-flight build finishes without consuming it. Build failures are swallowed
     * here, they resurface when the render pass joins the same future via [take]. A no-op when
     * nothing is pending, so hosts can call it unconditionally before dispatching input.
     */
    fun await() {
        val future = pending ?: return
        runCatching { future.join() }
    }

    /** Waits out any in-flight build and drops it, call on teardown, content reset or resize. */
    fun reset() {
        await()
        pending = null
    }

    internal companion object {
        /** Builds a surface without a frame of latency while still sharing the layout thread. */
        fun buildSerialized(build: () -> HollowUiFrame): HollowUiFrame =
            CompletableFuture.supplyAsync(build, Executor).join()

        private val Executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "HollowEngine UI Frame Builder").apply { isDaemon = true }
        }
    }
}
