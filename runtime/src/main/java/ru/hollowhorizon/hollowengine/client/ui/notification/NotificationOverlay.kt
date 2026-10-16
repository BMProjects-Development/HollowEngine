package ru.hollowhorizon.hollowengine.client.ui.notification

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.MinecraftHssResourceLoader
import ru.hollowhorizon.hollowengine.client.ui.widgets.Button
import ru.hollowhorizon.hollowengine.client.ui.widgets.IconButton
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderTickEvent

private const val NotificationStylesheet = "hollowengine:ui/styles/notifications.hss"
private const val CloseIcon = "hollowengine:textures/gui/icons/cross.svg"
private const val SliverWidth = 25f

/**
 * Draws the notification stack over everything else, screens included, and takes the clicks meant
 * for it before anything below sees them.
 */
@ClientOnly
object NotificationOverlay {
    private var overlay: HollowUiWorldOverlay? = null

    private val isActive: Boolean get() = HollowNotifications.entries.isNotEmpty()

    @SubscribeEvent
    fun render(event: RenderTickEvent.Blit) {
        HollowNotifications.advance(System.currentTimeMillis())
        if (!isActive) return
        host().render()
    }

    fun handleMouseButton(x: Float, y: Float, button: Int, action: Int): Boolean {
        if (!isActive) return false
        return host().handleMouseButton(x, y, button, action)
    }

    fun handleMouseMove(x: Float, y: Float): Boolean {
        val host = overlay ?: return false
        if (!isActive) return false
        return host.handleMouseMove(x, y)
    }

    fun handleMouseScroll(x: Float, y: Float, scrollX: Double, scrollY: Double): Boolean {
        val host = overlay ?: return false
        if (!isActive) return false
        return host.handleMouseScroll(x, y, scrollX, scrollY)
    }

    private fun host(): HollowUiWorldOverlay = overlay ?: HollowUiWorldOverlay(
        stylesheet = MinecraftHssResourceLoader.load(NotificationStylesheet),
        manageCursor = false,
    ).also {
        it.setContent { NotificationStack() }
        overlay = it
    }
}

@Composable
private fun NotificationStack() {
    Box(mode = UiBoxMode.STACK, modifier = Modifier.size(100.percent, 100.percent).inputTransparent()) {
        Column(
            id = "notification-stack",
            tags = listOf("notif-stack"),
            modifier = Modifier.align(UiAlign.END, UiAlign.END),
        ) {
            HollowNotifications.entries.forEach { notification ->
                key(notification.id) { NotificationCard(notification) }
            }
        }
    }
}

@Composable
private fun NotificationCard(notification: Notification) {
    Column(id = "notif-${notification.id}", tags = listOf("notif", notification.kind.tag)) {
        Row(tags = listOf("notif-head")) {
            Image(notification.icon, tags = listOf("notif-icon"))
            Text(notification.title, tags = listOf("notif-title"), modifier = Modifier.grow(1f))

            notification.actions.forEach { action ->
                ActionButton(notification, action)
            }
            if (notification.closable) {
                IconButton(CloseIcon, tooltip = null, id = "notif-close-${notification.id}", tags = listOf("notif-close")) {
                    HollowNotifications.dismiss(notification.id)
                }
            }
        }

        notification.progress?.let { ProgressBar(it) }
    }
}

@Composable
private fun ActionButton(notification: Notification, action: NotificationAction) = Button(
    action.label,
    id = "notif-action-${notification.id}-${action.label}",
    tags = listOf("notif-action"),
) { HollowNotifications.run(notification, action) }

@Composable
private fun ProgressBar(progress: Float) {
    Box(tags = listOf("notif-track")) {
        if (progress >= 0f) {
            Box(
                tags = listOf("notif-fill"),
                modifier = Modifier.size((progress.coerceIn(0f, 1f) * 100f).percent, 100.percent),
            )
        } else {
            val phase = ((LocalUiFrameTimeNanos.current / 1_000_000L) % 1600L) / 1600f
            Box(
                tags = listOf("notif-fill"),
                modifier = Modifier.size(SliverWidth.percent, 100.percent)
                    .position((phase * (100f - SliverWidth)).percent, 0.px),
            )
        }
    }
}
