import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import ru.hollowhorizon.hollowengine.common.events.ClientEvent
import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.events.ServerEvent
import ru.hollowhorizon.hollowengine.common.events.StartupEvent
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EventSideTests {
    private val scopes = mutableListOf<CoroutineScope>()

    private fun scope(side: LogicalSide? = null): CoroutineScope =
        CoroutineScope(if (side == null) Job() else Job() + side).also(scopes::add)

    @AfterTest
    fun cancelScopes() = scopes.forEach { it.cancel() }

    @Test
    fun `sided subscriptions hear only the posts of their side`() {
        val heard = mutableListOf<String>()
        PlainEvent.subscribe(scope(LogicalSide.SERVER)) { heard += "server" }
        PlainEvent.subscribe(scope(LogicalSide.CLIENT)) { heard += "client" }
        PlainEvent.subscribe(scope()) { heard += "both" }
        ClientTestEvent.subscribe(scope(LogicalSide.CLIENT)) { heard += "client event" }
        ClientTestEvent.subscribe(scope()) { heard += "client event, both" }

        // Off the render thread, so an event that does not name its side belongs to the server.
        PlainEvent.post(PlainEvent())
        ClientTestEvent.post(ClientTestEvent())

        assertEquals(listOf("server", "both", "client event", "client event, both"), heard)
    }

    @Test
    fun `a sided scope cannot subscribe to what its side never hears`() {
        assertFailsWith<IllegalArgumentException> {
            ClientTestEvent.subscribe(scope(LogicalSide.SERVER)) {}
        }
        assertFailsWith<IllegalArgumentException> {
            ServerTestEvent.subscribe(scope(LogicalSide.CLIENT)) {}
        }
        assertFailsWith<IllegalArgumentException> {
            StartupTestEvent.subscribe(scope(LogicalSide.CLIENT)) {}
        }

        var heard = 0
        StartupTestEvent.subscribe(scope()) { heard++ }
        StartupTestEvent.post(StartupTestEvent())
        assertEquals(1, heard)
    }

    class PlainEvent : Event {
        companion object : EventHandler<PlainEvent>()
    }

    class ClientTestEvent : ClientEvent {
        companion object : EventHandler<ClientTestEvent>()
    }

    class ServerTestEvent : ServerEvent {
        companion object : EventHandler<ServerTestEvent>()
    }

    class StartupTestEvent : StartupEvent {
        companion object : EventHandler<StartupTestEvent>()
    }
}
