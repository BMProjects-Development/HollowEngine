package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.client.ui.notification.HollowNotifications
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.network.RequestScriptExecutionPacket
import ru.hollowhorizon.hollowengine.common.network.ScriptExecutionOperation
import ru.hollowhorizon.hollowengine.common.network.ScriptExecutionStatePacket
import ru.hollowhorizon.hollowengine.common.network.ScriptExecutionState
import java.lang.ref.WeakReference

/** Only the server reports whether a script is running; local clicks never guess that state. */
internal object ScriptExecutionClient {
    val states = mutableStateMapOf<String, ScriptExecutionState>()
    private var connection = WeakReference<Any>(null)
    var allowed by mutableStateOf(false)
        private set

    @SubscribeEvent
    @ClientOnly
    fun onClientTick(event: TickEvent.Client) { synchronizeConnection() }

    private fun synchronizeConnection() {
        val current = Minecraft.getInstance().connection
        if (current !== connection.get()) {
            states.clear()
            allowed = false
            connection = WeakReference(current)
        }
    }

    fun run(path: String, state: String?) = send(path, ScriptExecutionOperation.RUN, state)
    fun stop(path: String) = send(path, ScriptExecutionOperation.STOP)

    private fun send(path: String, operation: ScriptExecutionOperation, state: String? = null) {
        Minecraft.getInstance().execute {
            synchronizeConnection()
            if (allowed) RequestScriptExecutionPacket(path, operation, state).send()
        }
    }

    fun accept(packet: ScriptExecutionStatePacket) {
        synchronizeConnection()
        if (packet.reset) states.clear()
        allowed = packet.allowed
        if (!allowed) return
        packet.states.forEach { (path, state) ->
            if (!packet.reset && state.error != null && states[path]?.error != state.error) {
                HollowNotifications.error("hollowengine.gui.ide.gutter.run_failed".lang(state.error))
            }
            if (state == ScriptExecutionState()) states.remove(path) else states[path] = state
        }
    }
}
