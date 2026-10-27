package ru.hollowhorizon.hollowengine.addons.narrative.scene

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.*
import ru.hollowhorizon.hollowengine.addons.narrative.story.ActionCommands
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCommand
import kotlin.time.Duration.Companion.milliseconds

/**
 * The commands of a scene: resolved to actions with the characters of the scene as their actors, journaled by the
 * story the scene plays in, temporary inside `@temporary` and given up when the time of their `@timeout` is out.
 */
internal class SceneCalls(private val player: ScenePlayer) {
    /** When each `@timeout` block entered gives up, in epoch milliseconds, by `address#pc` of the block. */
    val deadlines = HashMap<String, Long>()

    /**
     * Runs a command of the main flow at [pc] and returns where the flow goes next: past the command, or past the
     * `@timeout` block whose time ran out. Null when a track preempted it.
     */
    suspend fun mainFlow(address: String, pc: Int, instruction: StoryInstruction.Invoke): Int? {
        val program = player.programOf(address) ?: return pc + 1
        val timeout = nearestTimeout(address, program, instruction)
        var inTime = true
        val completed = call(instruction) { command, perform ->
            player.preemptible {
                inTime = within(timeout, instruction.call.function) {
                    if (command.action.reversible) player.host.journaled(
                        statementOf(program, instruction),
                        perform
                    ) else perform()
                }
            }
        }
        return when {
            !completed -> null
            inTime -> pc + 1
            else -> timeout!!.end
        }
    }

    /** Runs a command of a track at [pc], which cannot be preempted, and returns where the track goes next. */
    suspend fun track(
        address: String,
        program: StoryProgram,
        pc: Int,
        instruction: StoryInstruction.Invoke,
        journaled: suspend (statement: String, call: suspend () -> Unit) -> Unit,
    ): Int {
        val timeout = nearestTimeout(address, program, instruction)
        var inTime = true
        call(instruction) { command, perform ->
            inTime = within(timeout, instruction.call.function) {
                if (command.action.reversible) journaled(statementOf(program, instruction), perform) else perform()
            }
            true
        }
        return if (inTime) pc + 1 else timeout!!.end
    }

    /** Runs a `[command]` of a line as typing reaches it; returns false when a track preempted it. */
    suspend fun inline(call: StoryLineKind.FuncCall): Boolean =
        call(call.function, player.evaluateArgs(call.args), null, emptyMap(), null) { _, perform ->
            player.preemptible(perform)
        }

    fun startTimeout(address: String, pc: Int, instruction: StoryInstruction.TimeoutStart) {
        val millis = (player.evaluate(instruction.duration) as? StoryNumber)?.value?.toLong()
            ?: throw StoryRuntimeException("'@timeout' takes a time like 6s (line ${instruction.anchor.line + 1})")
        deadlines["$address#$pc"] = System.currentTimeMillis() + millis
    }

    private suspend fun call(
        instruction: StoryInstruction.Invoke,
        run: suspend (command: StoryCommand, perform: suspend () -> Unit) -> Boolean,
    ): Boolean {
        val call = instruction.call
        val metadata = call.metadata.mapValues { (_, expr) -> player.evaluate(expr) }
        return call(call.function, player.evaluateArgs(call.args), call.tag, metadata, instruction.temporary, run)
    }

    /**
     * Calls the command [function] with [evaluated] arguments through [run], which returns false when the call was
     * preempted, and reports a [tag] on the event bus. Inside `@temporary` its actions are [temporary].
     */
    private suspend fun call(
        function: String,
        evaluated: EvaluatedArgs,
        tag: String?,
        metadata: Map<String, StoryValue>,
        temporary: StoryTemporary?,
        run: suspend (command: StoryCommand, perform: suspend () -> Unit) -> Boolean,
    ): Boolean {
        val command = ActionCommands.resolve(function, evaluated.positional, evaluated.named)
        val bound = bindActors(command.signature, evaluated)
        val args = StoryArguments.bind(command.signature, bound.positional, bound.named, metadata)

        tag?.let { SceneEvent.ActionStarted.post(SceneEvent.ActionStarted(player, it, function, args)) }
        var reason = StoryActionEnd.COMPLETED
        try {
            val completed = run(command) {
                withContext(CurrentScene(player)) {
                    if (temporary != null) player.host.temporary(temporary.until) { command.call(args) } else command.call(
                        args
                    )
                }
            }
            if (!completed) reason = StoryActionEnd.CANCELLED
            return completed
        } catch (e: CancellationException) {
            reason = StoryActionEnd.CANCELLED
            throw e
        } catch (e: Throwable) {
            reason = StoryActionEnd.FAILED
            throw e
        } finally {
            tag?.let { SceneEvent.ActionEnded.post(SceneEvent.ActionEnded(player, it, function, args, reason)) }
        }
    }

    /**
     * Turns the bare names an actor parameter was written with into actors. Anything the scene does not know about is
     * left as it was, so the command reports the failure in its own words.
     */
    private fun bindActors(signature: StoryFunctionSignature, evaluated: EvaluatedArgs): EvaluatedArgs {
        fun coerce(param: StoryParam?, value: StoryValue): StoryValue {
            if (param?.type != StoryType.ACTOR) return value
            val name = (value as? StoryString)?.value ?: return value
            return player.resolveActor(name) ?: value
        }

        val positional =
            evaluated.positional.mapIndexed { index, value -> coerce(signature.params.getOrNull(index), value) }
        val named = evaluated.named.mapValues { (name, value) ->
            coerce(
                signature.params.firstOrNull { it.name == name },
                value
            )
        }
        return EvaluatedArgs(positional, named)
    }

    /** A `@timeout` block a command is in: how much of its time is left, and where it ends. */
    private class Timeout(val left: Long, val end: Int)

    /** The block of [instruction] whose time runs out first, or null when it is in none. */
    private fun nearestTimeout(address: String, program: StoryProgram, instruction: StoryInstruction.Invoke): Timeout? =
        instruction.timeouts.mapNotNull { pc ->
            val deadline = deadlines["$address#$pc"] ?: return@mapNotNull null
            val block = program.instructions[pc] as StoryInstruction.TimeoutStart
            Timeout(deadline - System.currentTimeMillis(), block.end)
        }.minByOrNull { it.left }

    /** Runs [block] within the time of [timeout]; returns false when the time ran out and the command was given up. */
    private suspend fun within(timeout: Timeout?, command: String, block: suspend () -> Unit): Boolean {
        if (timeout == null) {
            block()
            return true
        }
        if (timeout.left > 0 && withTimeoutOrNull(timeout.left.milliseconds) { block() } != null) return true
        HollowEngine.LOGGER.warn(
            "'@{}' of '{}' ran out of the time of its @timeout; the scene goes on after the block",
            command,
            player.address
        )
        return false
    }

    /** The statement a command is, `hash#label+offset`, which a journal record of it is kept for. */
    private fun statementOf(program: StoryProgram, instruction: StoryInstruction.Invoke) =
        "${program.sourceHash}#${instruction.anchor.label}+${instruction.anchor.offset}"
}

/** Evaluated arguments of a call, positional and named. */
internal class EvaluatedArgs(val positional: List<StoryValue>, val named: Map<String, StoryValue>)

internal fun ScenePlayer.evaluateArgs(args: List<StoryArg>): EvaluatedArgs {
    val positional = mutableListOf<StoryValue>()
    val named = LinkedHashMap<String, StoryValue>()
    for (arg in args) {
        val value = evaluate(arg.expr)
        if (arg.name == null) positional += value else named[arg.name] = value
    }
    return EvaluatedArgs(positional, named)
}
