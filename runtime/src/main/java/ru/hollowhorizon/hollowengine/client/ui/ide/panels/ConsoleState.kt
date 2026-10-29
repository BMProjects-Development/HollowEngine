package ru.hollowhorizon.hollowengine.client.ui.ide.panels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.spi.StandardLevel
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.scripting.ConsoleScripts
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeEditorSession
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextDiagnostic
import ru.hollowhorizon.hollowengine.common.logging.HollowLogStore
import ru.hollowhorizon.hollowengine.common.scripting.source.SandboxScriptSource

/** What the console's input line does with its text. */
internal enum class ConsoleInputMode(val langKey: String) {
    COMMAND("hollowengine.gui.console.mode.command"), KOTLIN("hollowengine.gui.console.mode.kotlin"),
}

/** The pages of the bottom tool window. */
internal enum class ConsoleTab(val langKey: String) {
    LOGS("hollowengine.gui.console.tab.logs"), PROBLEMS("hollowengine.gui.console.tab.problems"),
}

/**
 * The console's state, kept by the IDE rather than by the panel's composition.
 */
internal class HollowIdeConsole {
    var tab by mutableStateOf(ConsoleTab.LOGS)
    var minimumLevel by mutableStateOf(StandardLevel.DEBUG)
    var filterText by mutableStateOf("")
    var autoScroll by mutableStateOf(true)
    var firstVisibleLogId by mutableStateOf(0L)
        private set

    var mode by mutableStateOf(ConsoleInputMode.COMMAND)
    var expanded by mutableStateOf(false)
    var running by mutableStateOf(false)
        private set

    var assistRevision by mutableStateOf(0L)
        private set

    private val inputs = mutableStateMapOf<ConsoleInputMode, String>()
    private val history = mapOf(
        ConsoleInputMode.COMMAND to ConsoleHistory(VanillaCommandHistory),
        ConsoleInputMode.KOTLIN to ConsoleHistory(ConsoleSnippetHistory),
    )

    val commands = ConsoleCommandAssist { assistRevision++ }

    /** Analysis of the Kotlin input, created the first time the Kotlin mode is used. */
    val kotlin: HollowIdeEditorSession by lazy { HollowIdeEditorSession(KotlinInputPath) { assistRevision++ } }

    var input: String
        get() = inputs[mode].orEmpty()
        set(value) {
            if (inputs[mode] == value) return
            inputs[mode] = value
            if (mode != ConsoleInputMode.KOTLIN) return
            if ('\n' in value) expanded = true
            kotlin.requestAnalysis(value, value.length)
        }

    /** Commands need a world to run in; Kotlin snippets run anywhere. */
    val canRun: Boolean
        get() = !running && input.isNotBlank() && (mode == ConsoleInputMode.KOTLIN || Minecraft.getInstance().connection != null)

    /** Whether a snippet is compiling, running, or left coroutines of its own behind. */
    val canStop: Boolean get() = running || ConsoleScripts.hasRunningWork

    fun diagnostics(): List<UiTextDiagnostic> = when (mode) {
        ConsoleInputMode.COMMAND -> commands.diagnostics(input)
        ConsoleInputMode.KOTLIN -> kotlin.diagnostics(input)
    }

    fun run() {
        if (!canRun) return
        val text = input
        when (mode) {
            ConsoleInputMode.COMMAND -> runCommand(text.trim())
            ConsoleInputMode.KOTLIN -> {
                history.getValue(mode).add(text)
                running = true
                ConsoleScripts.run(text) { running = false }
            }
        }
        input = ""
    }

    fun stopSnippets() = ConsoleScripts.stop()

    /** Advances only this console's view; log consumers and the persistent log keep their entries. */
    fun clearLogView() {
        firstVisibleLogId = HollowLogStore.snapshot().entries.lastOrNull()?.id?.plus(1) ?: 0L
    }

    /** Steps through earlier inputs of the current mode; [older] goes back in time. */
    fun browseHistory(older: Boolean): Boolean {
        input = history.getValue(mode).step(input, older) ?: return false
        return true
    }

    private fun runCommand(command: String) {
        val connection = Minecraft.getInstance().connection ?: return
        history.getValue(ConsoleInputMode.COMMAND).add(command)
        connection.sendCommand(command.removePrefix("/"))
        HollowEngine.LOGGER.info("Executed command: {}", command)
    }

    private companion object {
        /** Named like a project script so imports resolve, with the console's own script type. */
        val KotlinInputPath = "${SandboxScriptSource.SCRIPTS_DIRECTORY}/${ConsoleScripts.SCRIPT_NAME}"
    }
}
