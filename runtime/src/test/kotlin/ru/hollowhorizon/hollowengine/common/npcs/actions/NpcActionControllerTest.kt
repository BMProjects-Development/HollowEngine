package ru.hollowhorizon.hollowengine.common.npcs.actions

import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class NpcActionControllerTest {
    @Test
    fun `starting an action cancels the previous action in the same channel`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NpcActionController(CoroutineScope(SupervisorJob() + dispatcher))
        val gate = CompletableDeferred<Unit>()
        val first = controller.start(NpcActionKeys.MOVEMENT) {
            gate.await()
            1
        }

        runCurrent()
        val second = controller.start(NpcActionKeys.MOVEMENT) { 2 }
        runCurrent()

        assertFalse(first.isActive)
        assertEquals(NpcActionStatus.CANCELLED, first.status)
        assertEquals(2, second.await())
        assertEquals(NpcActionStatus.COMPLETED, second.status)
    }

    @Test
    fun `custom action keys run independently`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NpcActionController(CoroutineScope(SupervisorJob() + dispatcher))
        val movementGate = CompletableDeferred<Unit>()
        val movement = controller.start(NpcActionKeys.MOVEMENT) { movementGate.await() }
        val custom = controller.start(NpcActionKey("test:custom")) { "completed" }

        runCurrent()

        assertEquals("completed", custom.await())
        assertEquals(NpcActionStatus.RUNNING, movement.status)
        movement.cancel()
        runCurrent()
        assertEquals(NpcActionStatus.CANCELLED, movement.status)
    }

    @Test
    fun `cancelling an awaiter cancels the action`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NpcActionController(CoroutineScope(SupervisorJob() + dispatcher))
        val action = controller.start(NpcActionKeys.MOVEMENT) { awaitCancellation() }
        val awaiter = launch(dispatcher) { action.await() }

        runCurrent()
        awaiter.cancel()
        runCurrent()

        assertFalse(action.isActive)
        assertEquals(NpcActionStatus.CANCELLED, action.status)
    }
    @Test
    fun `concurrent actions on the same channel remain active`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NpcActionController(CoroutineScope(SupervisorJob() + dispatcher))
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val first = controller.startConcurrent(NpcActionKeys.ANIMATION) { firstGate.await() }
        val second = controller.startConcurrent(NpcActionKeys.ANIMATION) { secondGate.await() }

        runCurrent()

        assertEquals(NpcActionStatus.RUNNING, first.status)
        assertEquals(NpcActionStatus.RUNNING, second.status)
        controller.cancel(NpcActionKeys.ANIMATION)
        runCurrent()
        assertEquals(NpcActionStatus.CANCELLED, first.status)
        assertEquals(NpcActionStatus.CANCELLED, second.status)
    }
}
