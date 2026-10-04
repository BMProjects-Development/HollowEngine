package ru.hollowhorizon.hollowengine.client.ui.ide.panels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.GameViewportNodeId
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeGameViewport
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.utils.lang

internal object GameViewportLang {
    private const val ROOT = "hollowengine.gui.ide.windows.game_viewport"

    const val DETACHED = "$ROOT.detached"
}

/**
 * The game, drawn as panel of editor. Frame is rendered straight into a target
 * cut down to this panel, so what it shows is the game at its own resolution rather than a
 * shrunken copy of the window.
 */
@Composable
fun GameViewportDock(active: Boolean = false, attached: Boolean = true) {
    val placement = remember { PanelPlacement() }
    DisposableEffect(attached) {
        val bounds = placement.bounds
        if (attached && bounds != null) HollowIdeGameViewport.report(placement, bounds)
        if (!attached) HollowIdeGameViewport.release(placement)
        onDispose { HollowIdeGameViewport.release(placement) }
    }
    val dragAndDrop = LocalDragAndDrop.current
    val dropping = dragAndDrop?.hoveredTargetId == GameViewportDrop.TARGET_ID && dragAndDrop.canDrop
    val marker = GameViewportDrop.preview.takeIf { dropping }
    val drop = if (!attached) Modifier else Modifier.dropTarget(
        id = GameViewportDrop.TARGET_ID,
        accepts = GameViewportDrop::accepts,
        onDragOver = { _, x, y -> GameViewportDrop.hover(placement.imageBounds(), x, y) },
        onDrop = { item, x, y -> GameViewportDrop.drop(item, placement.imageBounds(), x, y) },
    )
    LaunchedEffect(dropping) { if (!dropping) GameViewportDrop.clear() }
    Box(
        id = GameViewportNodeId,
        tags = listOfNotNull("game-viewport", "active".takeIf { active && attached }),
        modifier = Modifier.input(hoverable = true, clickable = true)
            .onPlaced {
                placement.bounds = it
                if (attached) HollowIdeGameViewport.report(placement, it)
            }
            .then(drop)
            .drawBehind(key = "game-viewport-$attached-$marker") {
                if (!attached) return@drawBehind
                val rect = HollowIdeGameViewport.imageRect(UiRect(0f, 0f, size.width, size.height)) ?: return@drawBehind
                drawTexture(
                    rect = rect,
                    texture = { Minecraft.getInstance().mainRenderTarget?.colorTextureId ?: 0 },
                    flipY = true,
                    opaque = true,
                )
                marker?.let { GameViewportDrop.drawMarker(this, rect, it) }
            },
    ) {
        if (!attached) {
            Text(
                GameViewportLang.DETACHED.lang,
                tags = listOf("game-viewport-hint"),
                modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER).padding(16.px),
            )
        }
    }
}

private class PanelPlacement {
    var bounds: UiRect? = null

    /** Where the game image sits on the IDE surface. */
    fun imageBounds(): UiRect? = bounds?.let { HollowIdeGameViewport.imageRect(it) }
}
