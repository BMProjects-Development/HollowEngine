package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.*
import ru.hollowhorizon.hollowengine.addons.narrative.story.ActionCommands
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCommand
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs one story. [start] plays from wherever the controller currently stands, the beginning, or
 * the previous restored checkpoint.
 */
class DialogueController(
    override val address: String,
    val presentation: DialoguePresenter,
    val library: StoryLibrary,
    val votePolicy: VotePolicy = VotePolicy(),
    /** Locale to look for; null plays the base story. */
    val locale: String? = null,
) : DialogueSession {

    override lateinit var server: MinecraftServer
        private set

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

    /** Address and hash of the story that is playing, what a checkpoint records. */
    private var playingAddress: String = address
    private var playingLocale: String? = null

    private val tracks = LinkedHashMap<String, TrackHandle>()
    private var trackCounter = 0

    private class TrackHandle(val name: String, val anchor: StoryAnchor, var job: Job? = null)

    /** A track that hit `@sync` hands its remaining position over through this channel. */
    private val preemptions = Channel<Int>(Channel.CONFLATED)

    private var advanceSignal: CompletableDeferred<Unit>? = null

    /** An advance that arrived while nobody was waiting; consumed by the next wait. */
    private var pendingAdvance = false
    private var pendingMenu: PendingMenu? = null

    private class PendingMenu(val optionCount: Int) {
        val votes = LinkedHashMap<ServerPlayer, Int>()
        val signal = CompletableDeferred<Unit>()

        /** Set when a script decides for everyone; skips the vote entirely. */
        var forced: Int? = null
    }

    var isRunning: Boolean = false
        private set

    /** Variables the dialogue shares with whoever plays it, looked up before its own. */
    internal var outerVariables: StoryVariableSource? = null

    /** The story the dialogue plays in as a scene, if any. */
    internal var host: DialogueHost? = null

    /** When each `@timeout` block entered gives up, by `address#pc` of the block. */
    private val deadlines = HashMap<String, Long>()

    /** Checkpoint restored by [load], consumed by the next [start]. */
    private var restored: StoryCheckpoint? = null

    private val choiceHandlers = mutableListOf<(StoryChoiceEvent) -> Unit>()
    private val startHandlers = LinkedHashMap<String, MutableList<(StoryActionEvent) -> Unit>>()
    private val endHandlers = LinkedHashMap<String, MutableList<(StoryActionEndEvent) -> Unit>>()
    private val dialogueStartHandlers = mutableListOf<(DialogueSession) -> Unit>()
    private val dialogueEndHandlers = mutableListOf<(DialogueSession, DialogueResult) -> Unit>()

    fun onChoice(handler: (StoryChoiceEvent) -> Unit) {
        choiceHandlers += handler
    }

    /** Fires when a `tag=`-marked action begins. */
    fun onStart(tag: String, handler: (StoryActionEvent) -> Unit) {
        startHandlers.getOrPut(tag) { mutableListOf() } += handler
    }

    /** Fires when a `tag=`-marked action finishes, including when its track was cancelled. */
    fun onEnd(tag: String, handler: (StoryActionEndEvent) -> Unit) {
        endHandlers.getOrPut(tag) { mutableListOf() } += handler
    }

    /** Fires once per [start], before the first statement. */
    fun onDialogStart(handler: (DialogueSession) -> Unit) {
        dialogueStartHandlers += handler
    }

    /** Fires when the dialogue stops for any reason. */
    fun onDialogEnd(handler: (DialogueSession, DialogueResult) -> Unit) {
        dialogueEndHandlers += handler
    }

    /** Binds a character to the name stories use before the colon. */
    fun character(name: String, character: DialogueCharacter) {
        boundCharacters[name] = character
    }

    /**
     * Plays the story with [players] as its participants, from the current position, and returns how
     * it ended. Characters and participants are supplied fresh every time, an entity can never come
     * back from a checkpoint.
     */
    suspend fun start(vararg players: ServerPlayer, setup: DialogueStartScope.() -> Unit = {}): DialogueResult {
        require(players.isNotEmpty()) { "A dialogue needs at least one participant" }
        participants = players.toList()
        server = players.first().server
        return run(setup)
    }

    /**
     * Plays with no participants at all. Useful for a story that only drives the world (and for
     * tests): nothing is shown to anyone unless the presenter says otherwise, and the
     * "everyone left" ending cannot trigger.
     */
    suspend fun startHeadless(setup: DialogueStartScope.() -> Unit = {}): DialogueResult {
        participants = emptyList()
        return run(setup)
    }

    private suspend fun run(setup: DialogueStartScope.() -> Unit): DialogueResult {
        check(!isRunning) { "Dialogue '$address' is already running" }
        DialogueStartScope(this).setup()

        prepare()
        isRunning = true
        DialogueInput.register(this)
        dialogueStartHandlers.forEach { it(this) }
        DialogueEvent.Started.post(DialogueEvent.Started(this))

        var result: DialogueResult = DialogueResult.Finished
        try {
            coroutineScope {
                presentation.onDialogueStart(this@DialogueController)
                restoreTracks(this)
                result = runLoop(this)
                cancelTracks()
            }
        } catch (e: CancellationException) {
            // The scope died (server stopping): keep the checkpoint so the script can resume later.
            result = DialogueResult.Cancelled
            throw e
        } catch (e: Throwable) {
            result = DialogueResult.Failed(e)
        } finally {
            isRunning = false
            DialogueInput.unregister(this)
            val ending = result
            if (ending is DialogueResult.Finished) clearState()
            runCatching { presentation.onDialogueEnd(this, ending) }
            dialogueEndHandlers.forEach { it(this, ending) }
            DialogueEvent.Ended.post(DialogueEvent.Ended(this, ending))
        }
        return result
    }

    /** Starts the dialogue in [scope] without waiting for it, for scripts that fire and forget. */
    fun launchIn(scope: CoroutineScope, vararg players: ServerPlayer, setup: DialogueStartScope.() -> Unit = {}): Job =
        scope.launch { start(*players, setup = setup) }

    /** Forgets the position and variables, so the next [start] plays from the beginning. */
    fun reset() {
        check(!isRunning) { "Cannot reset a running dialogue" }
        clearState()
    }

    private fun clearState() {
        frames.clear()
        variables.clear()
        tracks.clear()
        deadlines.clear()
        restored = null
    }

    /** Writes the current position into [tag] under [key]. Safe to call while the dialogue runs. */
    fun save(key: String, tag: CompoundTag) {
        val checkpoint = checkpoint()
        if (checkpoint == null) tag.remove(key) else tag.put(key, checkpoint.save())
    }

    /** Restores a position previously written by [save]; the next [start] continues from it. */
    fun load(key: String, tag: CompoundTag) {
        check(!isRunning) { "Cannot load into a running dialogue" }
        restored = if (tag.contains(key)) StoryCheckpoint.load(tag.getCompound(key)) else null
    }

    /** Current position, or null when nothing is in progress. */
    fun checkpoint(): StoryCheckpoint? {
        if (!isRunning && frames.isEmpty()) return restored
        val program = programs[playingAddress] ?: return restored
        if (frames.isEmpty()) return null
        return StoryCheckpoint(
            address = playingAddress,
            sourceHash = program.sourceHash,
            locale = playingLocale,
            frames = frames.map { frame ->
                val frameProgram = programs[frame.address] ?: program
                CheckpointFrame(frame.address, frameProgram.anchorOf(frame.pc), frame.locals.toMap(), frame.result)
            },
            variables = variables.toMap(),
            tracks = tracks.values.map {
                CheckpointTrack(
                    it.name.takeUnless { n -> n.startsWith(ANONYMOUS_TRACK) },
                    it.anchor
                )
            },
            deadlines = deadlines.toMap(),
        )
    }

    /** Resolves the story and decides where to continue: exactly, from a label, or from the start. */
    private fun prepare() {
        val checkpoint = restored
        if (checkpoint == null) {
            val loaded = library.load(address, locale)
            playingAddress = loaded.address
            playingLocale = loaded.locale
            programs[loaded.address] = loaded.program
            frames.clear()
            frames.addLast(Frame(loaded.address, 0))
            return
        }

        val loaded = runCatching {
            LoadedStory(
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
        frames.clear()
        deadlines.clear()

        val exact = loaded.program.sourceHash == checkpoint.sourceHash && loaded.address == checkpoint.address
        if (exact) {
            val resolved = checkpoint.frames.map { frame ->
                val program = programOf(frame.address) ?: return@map null
                program.resolve(frame.anchor)?.let { Frame(frame.address, it, LinkedHashMap(frame.locals), frame.result) }
            }
            if (resolved.all { it != null }) {
                resolved.forEach { frames.addLast(it!!) }
                deadlines += checkpoint.deadlines
                checkpoint.tracks.forEach { track ->
                    loaded.program.resolve(track.anchor)?.let { pc ->
                        pendingTrackRestarts += track.name to pc
                    }
                }
                return
            }
        }

        // A `@define` cannot be reentered without its caller: the flow goes back to the label of the frame that called it.
        val innermost = checkpoint.frames.lastOrNull { !isDefineSection(it.anchor.label) } ?: checkpoint.frames.first()
        val labelStart = loaded.program.resolveLabelStart(innermost.anchor)
        frames.addLast(Frame(loaded.address, labelStart ?: 0))
    }

    private val pendingTrackRestarts = mutableListOf<Pair<String?, Int>>()

    private fun restoreTracks(scope: CoroutineScope) {
        val restarts = pendingTrackRestarts.toList()
        pendingTrackRestarts.clear()
        for ((name, pc) in restarts) {
            val program = programs[playingAddress] ?: continue
            val instruction = program.instructions.getOrNull(pc) as? StoryInstruction.AsyncStart ?: continue
            spawnTrack(scope, playingAddress, name, instruction)
        }
    }

    private fun programOf(address: String): StoryProgram? =
        programs[address] ?: runCatching { library.loadExact(address) }.getOrNull()?.also { programs[address] = it }

    private suspend fun runLoop(scope: CoroutineScope): DialogueResult {
        while (true) {
            takePreemption()?.let { pc ->
                frames.clear()
                frames.addLast(Frame(playingAddress, pc))
            }
            if (frames.isEmpty()) return DialogueResult.Finished
            if (participants.isNotEmpty() && onlineParticipants.isEmpty()) return DialogueResult.AllPlayersLeft
            host?.step()

            val frame = frames.last()
            val program = programOf(frame.address) ?: return DialogueResult.Finished
            val instruction = program.instructions.getOrNull(frame.pc)
            if (instruction == null) {
                frames.removeLast()
                continue
            }
            val outcome = execute(scope, frame, instruction)
            if (outcome != null) return outcome
        }
    }

    /** Executes one instruction; a non-null result ends the dialogue. */
    private suspend fun execute(
        scope: CoroutineScope,
        frame: Frame,
        instruction: StoryInstruction,
    ): DialogueResult? {
        when (instruction) {
            is StoryInstruction.Say -> {
                val speaker = instruction.speakerExpr?.let { characterOf(evaluate(it)) }
                    ?: instruction.speaker?.let { resolveCharacter(it) }
                if (!sayLine(speaker, instruction.text)) return null
                frame.pc++
            }

            is StoryInstruction.Menu -> return runMenu(frame, instruction)

            is StoryInstruction.Set -> {
                assign(instruction.variable, evaluate(instruction.value))
                frame.pc++
            }

            is StoryInstruction.Goto -> frame.pc = instruction.target

            is StoryInstruction.GotoIfFalse -> {
                frame.pc = if (evaluate(instruction.condition).isTruthy()) frame.pc + 1 else instruction.target
            }

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
                frames.addLast(Frame(address, definition.start, LinkedHashMap(definition.params.zip(args).toMap()), instruction.result))
            }

            is StoryInstruction.Stage -> {
                val host = host ?: throw StoryRuntimeException("'@stage' works only in a scene of a story")
                val evaluated = instruction.args.toEvaluated()
                val value = host.stage(instruction.script, instruction.stage, evaluated.positional, evaluated.named)
                instruction.result?.let { name -> value?.let { assign(name, it) } }
                frame.pc++
            }

            is StoryInstruction.Invoke -> frame.pc = invoke(frame, instruction) ?: return null // preempted

            is StoryInstruction.TimeoutStart -> {
                startTimeout(frame.address, frame.pc, instruction)
                frame.pc++
            }

            is StoryInstruction.Checkpoint -> {
                frame.pc++
                host?.let {
                    it.step()
                    it.checkpoint(instruction.name)
                }
            }

            is StoryInstruction.Rewind -> {
                val host = host ?: throw StoryRuntimeException("'@rewind' works only in a scene of a story")
                host.rewind(instruction.name)
                awaitCancellation()
            }

            is StoryInstruction.Command -> {
                runCommand(render(instruction.text))
                frame.pc++
            }

            is StoryInstruction.AsyncStart -> {
                val host = host
                if (host == null) spawnTrack(scope, frame.address, instruction.trackName, instruction)
                else host.startTrack(instruction.trackName ?: "track-L${instruction.anchor.line + 1}", frame.address, instruction.anchor)
                frame.pc = instruction.bodyEnd
            }

            is StoryInstruction.Await -> {
                val host = host
                val awaited = if (host == null) {
                    val jobs = if (instruction.trackNames.isEmpty()) tracks.values.mapNotNull { it.job }
                    else instruction.trackNames.mapNotNull { tracks[it]?.job }
                    preemptible { jobs.joinAll() }
                } else {
                    val running = host.tracks()
                    val branches = if (instruction.trackNames.isEmpty()) running.values else instruction.trackNames.mapNotNull(running::get)
                    preemptible { branches.forEach { it.join() } }
                }
                if (!awaited) return null
                frame.pc++
            }

            is StoryInstruction.Cancel -> {
                cancelTrack(instruction.trackName)
                frame.pc++
            }

            is StoryInstruction.Sync -> {
                // Reaching `@sync` on the main flow means the track already became the main flow.
                frame.pc++
            }
        }
        return null
    }

    /** Returns false when a track preempted the line. */
    private suspend fun sayLine(speaker: DialogueCharacter?, template: TextTemplate): Boolean {
        if (!preemptible { presentation.beginLine(this, speaker) }) return false
        val buffer = StringBuilder()

        suspend fun flush(): Boolean {
            if (buffer.isEmpty()) return true
            val text = buffer.toString()
            buffer.clear()
            return preemptible { presentation.appendText(this, text) }
        }

        for (part in template.parts) {
            when (part) {
                is TextPart.Literal -> buffer.append(part.text)
                is TextPart.Interpolation -> buffer.append(evaluate(part.expr).display())
                is TextPart.InlineCall -> {
                    if (!flush()) return false
                    val evaluated = part.call.args.toEvaluated()
                    if (!callCommand(part.call.function, evaluated, null, emptyMap(), null) { _, perform ->
                            preemptible(
                                perform
                            )
                        }) return false
                }

                is TextPart.WaitInput -> {
                    if (!flush()) return false
                    if (!preemptible { presentation.waitForInput(this) }) return false
                }
            }
        }
        if (!flush()) return false
        return preemptible { presentation.endLine(this) }
    }

    private suspend fun runMenu(frame: Frame, menu: StoryInstruction.Menu): DialogueResult? {
        val available = menu.options.filter { option ->
            option.condition?.let { evaluate(it).isTruthy() } ?: true
        }
        if (available.isEmpty()) {
            frame.pc = menu.exit
            return null
        }

        val presented = available.mapIndexed { index, option ->
            PresentedChoice(
                index = index,
                id = option.id,
                text = render(option.text),
                metadata = option.args.associate { arg -> (arg.name ?: "") to evaluate(arg.expr) },
            )
        }

        val menuState = PendingMenu(presented.size)
        pendingMenu = menuState
        var winner = -1
        try {
            if (!preemptible { presentation.showChoices(this, presented) }) return null
            val decided = preemptible {
                if (votePolicy.timeoutMillis != null) withTimeoutOrNull(votePolicy.timeoutMillis.milliseconds) { menuState.signal.await() }
                else menuState.signal.await()
            }
            if (!decided) return null
            winner = menuState.forced?.coerceIn(0, presented.size - 1) ?: decideVote(menuState.votes, presented.size)
        } finally {
            pendingMenu = null
            runCatching { presentation.hideChoices(this, winner) }
        }

        val votes = menuState.votes
        val option = available[winner]
        val choice = presented[winner]
        choiceHandlers.forEach {
            it(StoryChoiceEvent(this, winner, choice.id, choice.text, choice.metadata, votes.toMap()))
        }
        DialogueEvent.Chosen.post(
            DialogueEvent.Chosen(this, winner, choice.id, choice.text, choice.metadata, votes.toMap()),
        )
        frame.pc = option.bodyStart
        return null
    }

    /** Most votes wins; ties are broken at random, and no votes at all picks the first option. */
    private fun decideVote(votes: Map<ServerPlayer, Int>, optionCount: Int): Int {
        if (votes.isEmpty()) return 0
        val tally = IntArray(optionCount)
        votes.values.forEach { if (it in 0 until optionCount) tally[it]++ }
        val best = tally.max()
        val leaders = tally.indices.filter { tally[it] == best }
        return if (leaders.size == 1) leaders.first() else leaders.random()
    }

    /**
     * Runs a command of the main flow and returns where the flow goes next: past the command, or past the `@timeout`
     * block whose time ran out. Null when a track preempted it.
     */
    private suspend fun invoke(frame: Frame, instruction: StoryInstruction.Invoke): Int? {
        val call = instruction.call
        val evaluated = call.args.toEvaluated()
        val metadata = call.metadata.mapValues { (_, expr) -> evaluate(expr) }
        val statement =
            programOf(frame.address)?.let { "${it.sourceHash}#${instruction.anchor.label}+${instruction.anchor.offset}" }
        val timeout = nearestTimeout(frame.address, instruction)
        var inTime = true
        val completed = callCommand(call.function, evaluated, call.tag, metadata, instruction.temporary) { command, perform ->
            val host = host
            preemptible {
                inTime = within(timeout, call.function) {
                    if (host != null && statement != null && command.action.reversible) host.journaled(statement, perform)
                    else perform()
                }
            }
        }
        return when {
            !completed -> null
            inTime -> frame.pc + 1
            else -> timeout!!.end
        }
    }

    /** A `@timeout` block a command is in: how much of its time is left, and where it ends. */
    private class Timeout(val left: Long, val end: Int)

    private fun startTimeout(address: String, pc: Int, instruction: StoryInstruction.TimeoutStart) {
        val millis = (evaluate(instruction.duration) as? StoryNumber)?.value?.toLong()
            ?: throw StoryRuntimeException("'@timeout' takes a time like 6s (line ${instruction.anchor.line + 1})")
        deadlines["$address#$pc"] = System.currentTimeMillis() + millis
    }

    /** The block of [instruction] whose time runs out first, or null when it is in none. */
    private fun nearestTimeout(address: String, instruction: StoryInstruction.Invoke): Timeout? {
        val program = programOf(address) ?: return null
        return instruction.timeouts.mapNotNull { pc ->
            val deadline = deadlines["$address#$pc"] ?: return@mapNotNull null
            val block = program.instructions[pc] as StoryInstruction.TimeoutStart
            Timeout(deadline - System.currentTimeMillis(), block.end)
        }.minByOrNull { it.left }
    }

    /** Runs [block] within the time of [timeout]; returns false when the time ran out and the command was given up. */
    private suspend fun within(timeout: Timeout?, command: String, block: suspend () -> Unit): Boolean {
        if (timeout == null) {
            block()
            return true
        }
        if (timeout.left > 0 && withTimeoutOrNull(timeout.left.milliseconds) { block() } != null) return true
        HollowEngine.LOGGER.warn("'@{}' of '{}' ran out of the time of its @timeout; the story goes on after the block", command, address)
        return false
    }

    /**
     * Calls the command [function] with [evaluated] arguments through [run], which returns false when the call was
     * preempted, and reports a [tag] to the handlers. Inside `@temporary` its actions are [temporary].
     */
    private suspend fun callCommand(
        function: String,
        evaluated: EvaluatedArgs,
        tag: String?,
        metadata: Map<String, StoryValue>,
        temporary: StoryTemporary?,
        run: suspend (command: StoryCommand, perform: suspend () -> Unit) -> Boolean,
    ): Boolean {
        val command = ActionCommands.resolve(function, evaluated.positional, evaluated.named)
        val bound = bindActors(command.signature, evaluated.positional, evaluated.named)
        val args = StoryArguments.bind(command.signature, bound.positional, bound.named, metadata)

        tag?.let { t ->
            startHandlers[t]?.forEach { it(StoryActionEvent(this, t, function, args)) }
            DialogueEvent.ActionStarted.post(DialogueEvent.ActionStarted(this, t, function, args))
        }
        var reason = StoryActionEnd.COMPLETED
        try {
            val completed = run(command) {
                withContext(CurrentDialogue(this)) {
                    val host = host
                    if (temporary != null && host != null) host.temporary(temporary.until) { command.call(args) }
                    else command.call(args)
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
            tag?.let { t ->
                endHandlers[t]?.forEach { it(StoryActionEndEvent(this, t, function, args, reason)) }
                DialogueEvent.ActionEnded.post(DialogueEvent.ActionEnded(this, t, function, args, reason))
            }
        }
    }

    private fun runCommand(command: String) {
        server.commands.performPrefixedCommand(commandSource(), command)
    }

    private fun commandSource() = server.createCommandSourceStack().withPermission(4).withSuppressedOutput()

    private fun spawnTrack(
        scope: CoroutineScope,
        address: String,
        name: String?,
        instruction: StoryInstruction.AsyncStart,
    ) {
        val trackName = name ?: "$ANONYMOUS_TRACK${trackCounter++}"
        tracks[trackName]?.job?.cancel()
        val handle = TrackHandle(trackName, instruction.anchor)
        tracks[trackName] = handle
        handle.job = scope.launch {
            try {
                runTrack(address, instruction.bodyStart, instruction.bodyEnd)
            } finally {
                if (tracks[trackName] === handle) tracks.remove(trackName)
            }
        }
    }

    /**
     * Plays the track of the `@async` at [asyncAnchor] in [address] for a branch of the story: from [saved] when the
     * story is the same text it was saved from ([savedHash]), from its start otherwise. [step] is told where the
     * track is before every statement, and [journaled] runs its reversible commands.
     */
    internal suspend fun playTrack(
        address: String,
        asyncAnchor: StoryAnchor,
        saved: StoryAnchor?,
        savedHash: String?,
        step: (hash: String, anchor: StoryAnchor) -> Unit,
        journaled: suspend (statement: String, call: suspend () -> Unit) -> Unit,
    ) {
        val program = programOf(address) ?: return
        val async = program.resolve(asyncAnchor)?.let(program.instructions::get) as? StoryInstruction.AsyncStart ?: return
        val body = async.bodyStart until async.bodyEnd
        val start = saved?.takeIf { savedHash == program.sourceHash }?.let(program::resolve)?.takeIf { it in body } ?: async.bodyStart
        runTrack(address, start, async.bodyEnd, { pc -> step(program.sourceHash, program.anchorOf(pc)) }, journaled)
    }

    private suspend fun runTrack(
        address: String,
        start: Int,
        end: Int,
        step: ((Int) -> Unit)? = null,
        journaled: (suspend (statement: String, call: suspend () -> Unit) -> Unit)? = null,
    ) {
        val program = programOf(address) ?: return
        var pc = start
        while (pc < end) {
            step?.invoke(pc)
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

                is StoryInstruction.Invoke -> {
                    val call = instruction.call
                    val evaluated = call.args.toEvaluated()
                    val metadata = call.metadata.mapValues { (_, expr) -> evaluate(expr) }
                    val timeout = nearestTimeout(address, instruction)
                    var inTime = true
                    val statement = "${program.sourceHash}#${instruction.anchor.label}+${instruction.anchor.offset}"
                    // A track cannot preempt itself, so its commands simply run.
                    callCommand(call.function, evaluated, call.tag, metadata, instruction.temporary) { command, perform ->
                        inTime = within(timeout, call.function) {
                            if (journaled != null && command.action.reversible) journaled(statement, perform) else perform()
                        }
                        true
                    }
                    pc = if (inTime) pc + 1 else timeout!!.end
                }

                is StoryInstruction.TimeoutStart -> {
                    startTimeout(address, pc, instruction)
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
        host?.tracks()?.get(name)?.cancel()
        tracks.remove(name)?.job?.cancel()
    }

    private fun cancelTracks() {
        host?.tracks()?.values?.forEach(DialogueTrack::cancel)
        tracks.values.forEach { it.job?.cancel() }
        tracks.clear()
    }

    private fun takePreemption(): Int? = preemptions.tryReceive().getOrNull()

    /**
     * Runs [block], but gives up the moment a track asks to take over, that is what makes `@sync`
     * able to interrupt a line the players are still reading or a menu nobody has answered.
     * Returns false when the block was preempted.
     */
    private suspend fun preemptible(block: suspend () -> Unit): Boolean = coroutineScope {
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

    override fun advance(player: ServerPlayer) {
        if (player !in participants) return
        advance()
    }

    /** Advances on everyone's behalf, for a script that moves the dialogue on by itself. */
    fun advance() {
        val waiting = advanceSignal
        if (waiting == null) pendingAdvance = true else waiting.complete(Unit)
    }

    override fun choose(player: ServerPlayer, index: Int) {
        val menu = pendingMenu ?: return
        if (player !in participants) return
        if (index !in 0 until menu.optionCount) return
        menu.votes[player] = index
        presentation.onVote(this, menu.votes.toMap())
        if (menu.votes.keys.containsAll(onlineParticipants)) menu.signal.complete(Unit)
    }

    /**
     * Decides the open menu for everyone, ignoring votes. A script uses this to answer on the
     * players' behalf; tests use it to drive a dialogue with no players at all.
     */
    fun forceChoice(index: Int) {
        val menu = pendingMenu ?: return
        menu.forced = index
        menu.signal.complete(Unit)
    }

    /** Options currently on screen, or empty when no menu is open. */
    val openMenuSize: Int get() = pendingMenu?.optionCount ?: 0

    /** Suspends until any participant advances; presenters call this from [DialoguePresenter.endLine]. */
    suspend fun awaitAdvance() {
        if (pendingAdvance) {
            pendingAdvance = false
            return
        }
        val signal = CompletableDeferred<Unit>()
        advanceSignal = signal
        try {
            signal.await()
        } finally {
            advanceSignal = null
            pendingAdvance = false
        }
    }

    /** True while a presenter is waiting for the players to move on. */
    val isAwaitingAdvance: Boolean get() = advanceSignal != null

    private fun resolveCharacter(name: String): DialogueCharacter = boundCharacters[name] ?: StringCharacter(name)

    /**
     * The speaker a `{...}:` prefix evaluated to. An actor already carries its character, entity and
     * all; anything else speaks under its displayed value, which is what lets `{player}: ...` work with
     * a plain string variable.
     */
    private fun characterOf(value: StoryValue): DialogueCharacter =
        (value as? StoryActor)?.character ?: resolveCharacter(value.display())

    /**
     * A character an argument names: one the script bound, a participant by their name, or `player`
     * for the first participant, the spelling a single-player story naturally reaches for.
     */
    fun resolveActor(name: String): StoryActor? {
        boundCharacters[name]?.let { return StoryActor(name, it) }
        val player = when {
            name.equals(PLAYER_ACTOR, ignoreCase = true) -> participants.firstOrNull()
            else -> participants.firstOrNull { it.name.string == name || it.gameProfile.name == name }
        } ?: return null
        return StoryActor(name, DialogueCharacter.of(player))
    }

    /**
     * Turns the bare names an actor parameter was written with into actors. Anything the dialogue does
     * not know about is left as it was, so the function itself reports the failure with its own words.
     */
    private fun bindActors(
        signature: StoryFunctionSignature?,
        positional: List<StoryValue>,
        named: Map<String, StoryValue>,
    ): EvaluatedArgs {
        if (signature == null) return EvaluatedArgs(positional, named)

        fun coerce(param: StoryParam?, value: StoryValue): StoryValue {
            if (param?.type != StoryType.ACTOR) return value
            val name = (value as? StoryString)?.value ?: return value
            return resolveActor(name) ?: value
        }

        val boundPositional = positional.mapIndexed { index, value -> coerce(signature.params.getOrNull(index), value) }
        val boundNamed = named.mapValues { (name, value) ->
            coerce(signature.params.firstOrNull { it.name == name }, value)
        }
        return EvaluatedArgs(boundPositional, boundNamed)
    }

    private fun resolveTarget(target: StoryTarget, currentAddress: String): Frame {
        val destinationAddress = target.address ?: currentAddress
        val program =
            programOf(destinationAddress) ?: throw StoryRuntimeException("Story '$destinationAddress' not found")
        programs[destinationAddress] = program
        val pc = when (val label = target.label) {
            null -> 0
            else -> program.labels[label]
                ?: throw StoryRuntimeException("Label '#$label' not found in '$destinationAddress'")
        }
        return Frame(destinationAddress, pc)
    }

    internal fun evaluate(expr: StoryExpression): StoryValue = try {
        expr.evaluate(::lookup)
    } catch (e: StoryEvalException) {
        throw StoryRuntimeException("${e.message} (line ${e.span.line + 1})", e)
    }

    private fun lookup(name: String): StoryValue? =
        frames.lastOrNull()?.locals?.get(name) ?: outerVariables?.get(name) ?: variables[name] ?: resolveActor(name)

    /** The `@define` [name] as the story [address] sees it: its own, or that of the first story it imports with one. */
    private fun findDefine(address: String, name: String): Pair<String, StoryDefinition> {
        val program = programOf(address) ?: throw StoryRuntimeException("Story '$address' not found")
        program.defines[name]?.let { return address to it }
        return program.imports.firstNotNullOfOrNull { imported -> programOf(imported)?.defines?.get(name)?.let { imported to it } }
            ?: throw StoryRuntimeException("No '@define $name' in '$address' or the stories it imports")
    }

    /** Writes a variable where it lives: with whoever plays the dialogue when it is theirs, in the dialogue otherwise. */
    private fun assign(name: String, value: StoryValue) {
        frames.lastOrNull()?.locals?.takeIf { name in it }?.let {
            it[name] = value
            return
        }
        if (outerVariables?.set(name, value) != true) variables[name] = value
    }

    private fun render(template: TextTemplate): String = buildString {
        for (part in template.parts) {
            when (part) {
                is TextPart.Literal -> append(part.text)
                is TextPart.Interpolation -> append(evaluate(part.expr).display())
                is TextPart.InlineCall, is TextPart.WaitInput -> Unit
            }
        }
    }

    private class EvaluatedArgs(val positional: List<StoryValue>, val named: Map<String, StoryValue>)

    private fun List<StoryArg>.toEvaluated(): EvaluatedArgs {
        val positional = mutableListOf<StoryValue>()
        val named = LinkedHashMap<String, StoryValue>()
        for (arg in this) {
            val value = evaluate(arg.expr)
            if (arg.name == null) positional += value else named[arg.name] = value
        }
        return EvaluatedArgs(positional, named)
    }

    companion object {
        private const val ANONYMOUS_TRACK = "track$"

        /** Reserved actor name for the first participant. */
        const val PLAYER_ACTOR = "player"

        /** Property lookups fail outside any one expression, so they report without a position. */
    }
}

/** Configuration applied at [DialogueController.start] time: variables, characters, the actor. */
class DialogueStartScope(private val controller: DialogueController) {
    fun variables(block: MutableMap<String, StoryValue>.() -> Unit) = controller.variables.block()

    fun put(name: String, value: String) = controller.variables.put(name, StoryString(value))
    fun put(name: String, value: Number) = controller.variables.put(name, StoryNumber(value.toFloat()))
    fun put(name: String, value: Boolean) = controller.variables.put(name, StoryBool(value))

    /** Binds a speaker name to a character. */
    fun character(name: String, character: DialogueCharacter) = controller.character(name, character)
}

class StoryRuntimeException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
