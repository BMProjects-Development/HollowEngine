package ru.hollowhorizon.hollowengine.client.scripting

import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.Component
import ru.hollowhorizon.hollowengine.common.events.EventListener
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent

/**
 * Tells player that startup scripts failed. They run before the game window exists, so the toast waits
 * for the first client tick.
 */
internal object StartupScriptNotice {
    fun show(scripts: List<String>) {
        TickEvent.Client.register(object : EventListener<TickEvent.Client> {
            override fun invoke(event: TickEvent.Client) {
                TickEvent.Client.unregister(this)
                event.minecraft.toasts.addToast(
                    SystemToast(
                        SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                        Component.translatable("hollowengine.gui.notification.title"),
                        Component.literal("Startup scripts failed, see the log: ${scripts.joinToString()}"),
                    ),
                )
            }
        })
    }
}
