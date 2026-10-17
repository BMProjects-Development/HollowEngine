import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers
import ru.hollowhorizon.hollowengine.common.ide.session.AnalysisEnvironment
import java.awt.EventQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertTrue

class AnalysisEnvironmentDisposalTest {
    @Test
    fun `disposal on the AWT thread also completes notifications before removing the application`() {
        val failures = ConcurrentLinkedQueue<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            if (thread.name.startsWith("AWT-EventQueue")) failures += failure
            else previous?.uncaughtException(thread, failure)
        }
        try {
            val environment = AnalysisEnvironment(emptyList())
            EventQueue.invokeAndWait {
                BinaryFileTypeDecompilers.getInstance().notifyDecompilerSetChange()
                environment.dispose()
            }
            EventQueue.invokeAndWait {}
            assertTrue(failures.isEmpty(), failures.joinToString("\n") { it.stackTraceToString() })
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `disposal waits for pending decompiler notifications before removing the application`() {
        val failures = ConcurrentLinkedQueue<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            if (thread.name.startsWith("AWT-EventQueue")) failures += failure
            else previous?.uncaughtException(thread, failure)
        }
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val environment = AnalysisEnvironment(emptyList())
        var disposal: CompletableFuture<Void>? = null
        try {
            EventQueue.invokeLater {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            BinaryFileTypeDecompilers.getInstance().notifyDecompilerSetChange()
            disposal = CompletableFuture.runAsync { environment.dispose() }
            try {
                disposal.get(100, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Correct disposal is waiting for the queued notification to be processed.
            }
            release.countDown()
            disposal.get(30, TimeUnit.SECONDS)
            EventQueue.invokeAndWait {}
            assertTrue(failures.isEmpty(), failures.joinToString("\n") { it.stackTraceToString() })
        } finally {
            release.countDown()
            disposal?.get(30, TimeUnit.SECONDS) ?: environment.dispose()
            EventQueue.invokeAndWait {}
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
