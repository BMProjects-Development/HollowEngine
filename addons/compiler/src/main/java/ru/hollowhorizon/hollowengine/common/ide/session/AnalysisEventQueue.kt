package ru.hollowhorizon.hollowengine.common.ide.session

import com.intellij.openapi.application.Application
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers
import com.intellij.openapi.util.Key
import com.intellij.psi.compiled.ClassFileDecompilers
import java.awt.EventQueue
import java.awt.Toolkit
import java.util.concurrent.CompletableFuture

private val DecompilerEventsInstalled = Key.create<Boolean>("hollowengine.decompiler.events")

@Suppress("DEPRECATION")
internal fun registerAnalysisEventDrain(application: Application) {
    if (application.getUserData(DecompilerEventsInstalled) == true) return
    BinaryFileTypeDecompilers.getInstance()
    val decompilers = ClassFileDecompilers.getInstance()
    decompilers.EP_NAME.addChangeListener(::awaitAnalysisEvents, application)
    ExtensionPointName.create<Any>("com.intellij.filetype.decompiler")
        .addChangeListener(::awaitAnalysisEvents, application)
    application.putUserData(DecompilerEventsInstalled, true)
}

internal fun awaitAnalysisEvents() {
    if (EventQueue.isDispatchThread()) {
        val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        EventQueue.invokeLater { loop.exit() }
        check(loop.enter()) { "Cannot finish pending analysis events" }
    } else {
        val barrier = CompletableFuture<Unit>()
        EventQueue.invokeLater { barrier.complete(Unit) }
        barrier.join()
    }
}
