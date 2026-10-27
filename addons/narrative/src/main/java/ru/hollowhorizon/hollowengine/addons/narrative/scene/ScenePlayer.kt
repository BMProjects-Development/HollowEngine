package ru.hollowhorizon.hollowengine.addons.narrative.scene

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.*

/**
 * Plays one scene, a `.story` file, for the story that called [ru.hollowhorizon.hollowengine.addons.narrative.story.scene]:
 * the [host] saves it after every statement, journals its commands and runs its tracks as branches.
 *
 * Lines and menus are [SceneLines], the answers of the participants [SceneAnswers], commands [SceneCalls].
 */
internal class ScenePlayer(
    override val address: String,
    val presentation: DialoguePresenter,
    private val library: SceneLibrary,
    val votePolicy: VotePolicy = VotePolicy(),
    private val locale: String? = null,
) : SceneSession {
    lateinit var host: SceneHost

    override val server: MinecraftServer get() = host.server

    override var participants: List<ServerPlayer> = emptyList()
        private set

    override val onlineParticipants: List<ServerPlayer>
        get() = participants.filter { !it.hasDisconnected() }

    override val variables: MutableMap<String, StoryValue> = LinkedHashMap()

    private val boundCharacters = LinkedHashMap<String, DialogueCharacter>()
    override val characters: Map<String, DialogueCharacter> get() = boundCharacters

    /**
     * A place in the call stack. A frame of a `@define` holds its parameters and the variables it sets as [locals],
     * and the variable of its caller its value goes to as [result].
     */
    private class Frame(
        val address: String,
        var pc: Int,
        val locals: MutableMap<String, StoryValue> = LinkedHashMap(),
        val result: String? = null,
    )

    private val frames = ArrayDeque<Frame>()
    private val programs = HashMap<String, StoryProgram>()

    /** Address and locale of the story that is playing, what a checkpoint records. */
    private var playingAddress: String = address
    private var playingLocale: String? = null

    /** A track that hit `@sync` hands its remaining position over through this channel. */
    private val preemptions = Channel<Int>(Channel.CONFLATED)

    private val answers = SceneAnswers(this, presentation)
    private val calls = SceneCalls(this)
    private val lines = SceneLines(this, answers, calls)

    var isRunning: Boolean = false
        private set

    /** Checkpoint to continue from, consumed by the next [play]. */
    private var restored: SceneCheckpoint? = null

    /** Binds a character to the name the story uses before the colon. */
    fun character(name: String, character: DialogueCharacter) {
        boundCharacters[name] = character
    }

    /** Continues from [checkpoint] on the next [play]: exactly, from a label, or from the start. */
    fun restore(checkpoint: SceneCheckpoint?) {
        check(!isRunning) { "Cannot restore a running scene" }
        restored = checkpoint
    }

    /** Plays the scene with [players] as its participants, none to play it to nobody, and returns how it ended. */
    suspend fun play(players: List<ServerPlayer>): SceneResult {
        check(!isRunning) { "Scene '$address' is already running" }
        participants = players
        prepare()
        isRunning = true
        DialogueInput.register(this)
        SceneEvent.Started.post(SceneEvent.Started(this))

        var result: SceneResult = SceneResult.Finished
        try {
            coroutineScope {
                presentation.onDialogueStart(this@ScenePlayer)
                result = runLoop()
                host.tracks().values.forEach(SceneTrack::cancel)
            }
        } catch (e: CancellationException) {
            // The story stopped or sleeps: its save keeps the checkpoint for the next play.
            result = SceneResult.Cancelled
            throw e
        } catch (e: Throwable) {
            result = SceneResult.Failed(e)
        } finally {
            isRunning = false
            DialogueInput.unregister(this)
            val ending = result
            runCatching { presentation.onDialogueEnd(this, ending) }
            SceneEvent.Ended.post(SceneEvent.Ended(this, ending))
        }
        return result
    }

    /** Where the scene is now, or null when it has not started. */
    fun checkpoint(): SceneCheckpoint? {
        val program = programs[playingAddress] ?: return restored
        if (frames.isEmpty()) return null
        return SceneCheckpoint(
            address = playingAddress,
            sourceHash = program.sourceHash,
            locale = playingLocale,
            frames = frames.map { frame ->
                val frameProgram = programs[frame.address] ?: program
                CheckpointFrame(frame.address, frameProgram.anchorOf(frame.pc), frame.locals.toMap(), frame.result)
            },
            variables = variables.toMap(),
            deadlines = calls.deadlines.toMap(),
        )
    }

    /** Resolves the story and decides where to continue: exactly, from a label, or from the start. */
    private fun prepare() {
        val checkpoint = restored
        frames.clear()
        calls.deadlines.clear()
        if (checkpoint == null) {
            val loaded = library.load(address, locale)
            playingAddress = loaded.address
            playingLocale = loaded.locale
            programs[loaded.address] = loaded.program
            frames.addLast(Frame(loaded.address, 0))
            return
        }

        val loaded = runCatching {
            LoadedScene(
                checkpoint.address,
                checkpoint.locale,
                library.loadExact(checkpoint.address)
            )
        }.getOrElse { library.load(address, locale) }
        playingAddress = loaded.address
        playingLocale = loaded.locale
        programs[loaded.address] = loaded.program
        variables.clear()
        variables += checkpoint.variables

        if (loaded.program.sourceHash == checkpoint.sourceHash && loaded.address == checkpoint.address) {
            val resolved = checkpoint.frames.map { frame ->
                programOf(frame.address)?.resolve(frame.anchor)
                    ?.let { Frame(frame.address, it, LinkedHashMap(frame.locals), frame.result) }
            }
            if (resolved.all { it != null }) {
                resolved.forEach { frames.addLast(it!!) }
                calls.deadlines += checkpoint.deadlines
                return
            }
        }

        // A `@define` cannot be reentered without its caller: the flow goes back to the label of the frame that called it.
        val innermost = checkpoint.frames.lastOrNull { !isDefineSection(it.anchor.label) } ?: checkpoint.frames.first()
        frames.addLast(Frame(loaded.address, loaded.program.resolveLabelStart(innermost.anchor) ?: 0))
    }

    fun programOf(address: String): StoryProgram? =
        programs[address] ?: runCatching { library.loadExact(address) }.getOrNull()?.also { programs[address] = it }

    private suspend fun runLoop(): SceneResult {
        while (true) {
            preemptions.tryReceive().getOrNull()?.let { pc ->
                frames.clear()
                frames.addLast(Frame(playingAddress, pc))
            }
            if (frames.isEmpty()) return SceneResult.Finished
            if (participants.isNotEmpty() && onlineParticipants.isEmpty()) return SceneResult.AllPlayersLeft
            host.step()

            val frame = frames.last()
            val program = programOf(frame.address) ?: return SceneResult.Finished
            val instruction = program.instructions.getOrNull(frame.pc)
            if (instruction == null) {
                frames.removeLast()
                continue
            }
            if (!execute(frame, instruction)) continue
        }
    }

    /** Executes one instruction of the main flow; returns false when a track preempted it, to run what it handed over. */
    private suspend fun execute(frame: Frame, instruction: StoryInstruction): Boolean {
        when (instruction) {
            is StoryInstruction.Say -> {
                val speaker = instruction.speakerExpr?.let { characterOf(evaluate(it)) }
                    ?: instruction.speaker?.let(::resolveCharacter)
                if (!lines.say(speaker, instruction.text)) return false
                frame.pc++
            }

            is StoryInstruction.Menu -> frame.pc = lines.menu(instruction) ?: return false

            is StoryInstruction.Set -> {
                assign(instruction.variable, evaluate(instruction.value))
                frame.pc++
            }

            is StoryInstruction.Goto -> frame.pc = instruction.target

            is StoryInstruction.GotoIfFalse -> frame.pc =
                if (evaluate(instruction.condition).isTruthy()) frame.pc + 1 else instruction.target

            is StoryInstruction.Jump -> {
                val destination = resolveTarget(instruction.target, frame.address)
                frames.clear()
                frames.addLast(destination)
                playingAddress = destination.address
            }

            is StoryInstruction.Call -> {
                frame.pc++
                frames.addLast(resolveTarget(instruction.target, frame.address))
            }

            is StoryInstruction.Return -> {
                val value = instruction.value?.let(::evaluate)
                val returning = frames.removeLast()
                if (returning.result != null && value != null) assign(returning.result, value)
            }

            is StoryInstruction.CallDefine -> {
                val (address, definition) = findDefine(frame.address, instruction.name)
                val args = instruction.args.map(::evaluate)
                frame.pc++
                frames.addLast(
                    Frame(
                        address,
                        definition.start,
                        LinkedHashMap(definition.params.zip(args).toMap()),
                        instruction.result
                    )
                )
            }

            is StoryInstruction.Stage -> {
                val evaluated = evaluateArgs(instruction.args)
                val value = host.stage(instruction.script, instruction.stage, evaluated.positional, evaluated.named)
                instruction.result?.let { name -> value?.let { assign(name, it) } }
                frame.pc++
            }

            is StoryInstruction.Invoke -> frame.pc =
                calls.mainFlow(frame.address, frame.pc, instruction) ?: return false

            is StoryInstruction.TimeoutStart -> {
                calls.startTimeout(frame.address, frame.pc, instruction)
                frame.pc++
            }

            is StoryInstruction.Checkpoint -> {
                frame.pc++
                host.step()
                host.checkpoint(instruction.name)
            }

            is StoryInstruction.Rewind -> {
                host.rewind(instruction.name)
                awaitCancellation()
            }

            is StoryInstruction.Command -> {
                runCommand(render(instruction.text))
                frame.pc++
            }

            is StoryInstruction.AsyncStart -> {
                host.startTrack(
                    instruction.trackName ?: "track-L${instruction.anchor.line + 1}",
                    frame.address,
                    instruction.anchor
                )
                frame.pc = instruction.bodyEnd
            }

            is StoryInstruction.Await -> {
                val running = host.tracks()
                val awaited =
                    if (instruction.trackNames.isEmpty()) running.values else instruction.trackNames.mapNotNull(running::get)
                if (!preemptible { awaited.forEach { it.join() } }) return false
                frame.pc++
            }

            is StoryInstruction.Cancel -> {
                cancelTrack(instruction.trackName)
                frame.pc++
            }

            // Reaching `@sync` on the main flow means the track already became the main flow.
            is StoryInstruction.Sync -> frame.pc++
        }
        return true
    }

    /**
     * Plays the track of the `@async` at [asyncAnchor] in [address] for a branch of the story: from [saved] when the
     * story is the same text it was saved from ([savedHash]), from its start otherwise. [step] is told where the
     * track is before every statement, and [journaled] runs its reversible commands.
     */
    suspend fun playTrack(
        address: String,
        asyncAnchor: StoryAnchor,
        saved: StoryAnchor?,
        savedHash: String?,
        step: (hash: String, anchor: StoryAnchor) -> Unit,
        journaled: suspend (statement: String, call: suspend () -> Unit) -> Unit,
    ) {
        val program = programOf(address) ?: return
        val async =
            program.resolve(asyncAnchor)?.let(program.instructions::get) as? StoryInstruction.AsyncStart ?: return
        val body = async.bodyStart until async.bodyEnd
        var pc = saved?.takeIf { savedHash == program.sourceHash }?.let(program::resolve)?.takeIf { it in body }
            ?: async.bodyStart
        while (pc < async.bodyEnd) {
            step(program.sourceHash, program.anchorOf(pc))
            when (val instruction = program.instructions.getOrNull(pc)) {
                null -> return
                is StoryInstruction.Sync -> {
                    preemptions.send(pc + 1)
                    return
                }

                is StoryInstruction.Goto -> pc = instruction.target
                is StoryInstruction.GotoIfFalse -> pc =
                    if (evaluate(instruction.condition).isTruthy()) pc + 1 else instruction.target

                is StoryInstruction.Set -> {
                    assign(instruction.variable, evaluate(instruction.value))
                    pc++
                }

                is StoryInstruction.Invoke -> pc = calls.track(address, program, pc, instruction, journaled)

                is StoryInstruction.TimeoutStart -> {
                    calls.startTimeout(address, pc, instruction)
                    pc++
                }

                is StoryInstruction.Command -> {
                    runCommand(render(instruction.text))
                    pc++
                }

                else -> pc++
            }
        }
    }

    override fun cancelTrack(name: String) {
        host.tracks()[name]?.cancel()
    }

    /**
     * Runs [block], but gives up the moment a track asks to take over: that is what makes `@sync` able to interrupt a
     * line the players are still reading or a menu nobody has answered. Returns false when the block was preempted.
     */
    suspend fun preemptible(block: suspend () -> Unit): Boolean = coroutineScope {
        val work = async { block() }
        val preempted = select {
            work.onAwait { false }
            preemptions.onReceive { pc ->
                work.cancel()
                preemptions.trySend(pc)
                true
            }
        }
        !preempted
    }

    override val openMenuSize: Int get() = answers.openMenuSize

    override fun advance(player: ServerPlayer) {
        if (player in participants) answers.advance()
    }

    override fun choose(player: ServerPlayer, index: Int) {
        if (player in participants) answers.choose(player, index)
    }

    override suspend fun awaitAdvance() = answers.awaitAdvance()

    /** Decides the open menu for everyone, ignoring votes. */
    fun forceChoice(index: Int) = answers.forceChoice(index)

    /**
     * A character an argument names: one the story bound, a participant by their name, or `player` for the first
     * participant, the spelling a single-player scene naturally reaches for.
     */
    fun resolveActor(name: String): StoryActor? {
        boundCharacters[name]?.let { return StoryActor(name, it) }
        val player = when {
            name.equals(PLAYER_ACTOR, ignoreCase = true) -> participants.firstOrNull()
            else -> participants.firstOrNull { it.name.string == name || it.gameProfile.name == name }
        } ?: return null
        return StoryActor(name, DialogueCharacter.of(player))
    }

    private fun resolveCharacter(name: String): DialogueCharacter = boundCharacters[name] ?: StringCharacter(name)

    /**
     * The speaker a `{...}:` prefix evaluated to. An actor already carries its character, entity and all; anything
     * else speaks under its displayed value, which is what lets `{player}: ...` work with a plain string variable.
     */
    private fun characterOf(value: StoryValue): DialogueCharacter =
        (value as? StoryActor)?.character ?: resolveCharacter(value.display())

    private fun resolveTarget(target: StoryTarget, currentAddress: String): Frame {
        val destination = target.address ?: currentAddress
        val program = programOf(destination) ?: throw StoryRuntimeException("Story '$destination' not found")
        val pc = target.label?.let { label ->
            program.labels[label] ?: throw StoryRuntimeException("Label '#$label' not found in '$destination'")
        } ?: 0
        return Frame(destination, pc)
    }

    /** The `@define` [name] as the story [address] sees it: its own, or that of the first story it imports with one. */
    private fun findDefine(address: String, name: String): Pair<String, StoryDefinition> {
        val program = programOf(address) ?: throw StoryRuntimeException("Story '$address' not found")
        program.defines[name]?.let { return address to it }
        return program.imports.firstNotNullOfOrNull { imported ->
            programOf(imported)?.defines?.get(name)?.let { imported to it }
        } ?: throw StoryRuntimeException("No '@define $name' in '$address' or the stories it imports")
    }

    fun evaluate(expr: StoryExpression): StoryValue = try {
        expr.evaluate(::lookup)
    } catch (e: StoryEvalException) {
        throw StoryRuntimeException("${e.message} (line ${e.span.line + 1})", e)
    }

    /** A variable: of the `@define` being played, of the story, of the scene, or a character. */
    private fun lookup(name: String): StoryValue? =
        frames.lastOrNull()?.locals?.get(name) ?: host.variables.get(name) ?: variables[name] ?: resolveActor(name)

    /** Writes a variable where it lives: in the `@define` being played, in the story, or in the scene. */
    private fun assign(name: String, value: StoryValue) {
        frames.lastOrNull()?.locals?.takeIf { name in it }?.let {
            it[name] = value
            return
        }
        if (!host.variables.set(name, value)) variables[name] = value
    }

    fun render(template: TextTemplate): String = buildString {
        for (part in template.parts) {
            when (part) {
                is TextPart.Literal -> append(part.text)
                is TextPart.Interpolation -> append(evaluate(part.expr).display())
                is TextPart.InlineCall, is TextPart.WaitInput -> Unit
            }
        }
    }

    private fun runCommand(command: String) {
        server.commands.performPrefixedCommand(
            server.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command
        )
    }

    companion object {
        /** Reserved actor name for the first participant. */
        const val PLAYER_ACTOR = "player"
    }
}

class StoryRuntimeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
