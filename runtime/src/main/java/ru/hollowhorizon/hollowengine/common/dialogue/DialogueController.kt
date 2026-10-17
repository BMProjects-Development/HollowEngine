package ru.hollowhorizon.hollowengine.common.dialogue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.common.dialogue.lang.StoryAnchor
import ru.hollowhorizon.hollowengine.common.dialogue.lang.StoryArg
import ru.hollowhorizon.hollowengine.common.dialogue.lang.StoryCall
import ru.hollowhorizon.hollowengine.common.dialogue.lang.StoryEvalException
import ru.hollowhorizon.hollowengine.common.dialogue.lang.StoryExpression
import ru.hollowhorizon.hollowengine.common.dialogue.lang.StoryFunctionSignature
import ru.hollowhorizon.hollowengine.common.dialogue.lang.StoryInstruction
import ru.hollowhorizon.hollowengine.common.dialogue.lang.StoryParam
import ru.hollowhorizon.hollowengine.common.dialogue.lang.StoryProgram
import ru.hollowhorizon.hollowengine.common.dialogue.lang.StorySpan
import ru.hollowhorizon.hollowengine.common.dialogue.lang.StoryType
import ru.hollowhorizon.hollowengine.common.dialogue.lang.StoryTarget
import ru.hollowhorizon.hollowengine.common.dialogue.lang.TextPart
import ru.hollowhorizon.hollowengine.common.dialogue.lang.TextTemplate
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs one story. [start] plays from wherever the controller currently stands, the beginning, or
 * the previous restored checkpoint.
 */
class DialogueController(
    override val address: String,
    val presentation: DialoguePresenter,
    val functions: StoryFunctionRegistry,
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

    private class Frame(val address: String, var pc: Int)

    private val frames = ArrayDeque<Frame>()
    private val programs = HashMap<String, StoryProgram>()

    /** Address and hash of the story that is playing, what a checkpoint records. */
    private var playingAddress: String = address
    private var playingLocale: String? = null

    /** Checkpoint of the function currently running; empty between statements. */
    private var callState = CompoundTag()

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
        callState = CompoundTag()
        tracks.clear()
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
                CheckpointFrame(frame.address, frameProgram.anchorOf(frame.pc))
            },
            variables = variables.toMap(),
            callState = callState.copy(),
            tracks = tracks.values.map { CheckpointTrack(it.name.takeUnless { n -> n.startsWith(ANONYMOUS_TRACK) }, it.anchor) },
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

        val loaded = runCatching { LoadedStory(checkpoint.address, checkpoint.locale, library.loadExact(checkpoint.address)) }
            .getOrElse { library.load(address, locale) }
        playingAddress = loaded.address
        playingLocale = loaded.locale
        programs[loaded.address] = loaded.program

        variables.clear()
        variables += checkpoint.variables
        callState = checkpoint.callState.copy()
        frames.clear()

        val exact = loaded.program.sourceHash == checkpoint.sourceHash && loaded.address == checkpoint.address
        if (exact) {
            val resolved = checkpoint.frames.map { frame ->
                val program = programOf(frame.address) ?: return@map null
                program.resolve(frame.anchor)?.let { Frame(frame.address, it) }
            }
            if (resolved.all { it != null }) {
                resolved.forEach { frames.addLast(it!!) }
                checkpoint.tracks.forEach { track ->
                    loaded.program.resolve(track.anchor)?.let { pc ->
                        pendingTrackRestarts += track.name to pc
                    }
                }
                return
            }
        }

        callState = CompoundTag()
        val innermost = checkpoint.frames.last()
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
                variables[instruction.variable] = evaluate(instruction.value)
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

            is StoryInstruction.Return -> frames.removeLast()

            is StoryInstruction.Invoke -> {
                if (!invoke(instruction.call)) return null // preempted
                frame.pc++
            }

            is StoryInstruction.Command -> {
                runCommand(render(instruction.text))
                frame.pc++
            }

            is StoryInstruction.AsyncStart -> {
                spawnTrack(scope, frame.address, instruction.trackName, instruction)
                frame.pc = instruction.bodyEnd
            }

            is StoryInstruction.Await -> {
                val jobs = if (instruction.trackNames.isEmpty()) tracks.values.mapNotNull { it.job }
                else instruction.trackNames.mapNotNull { tracks[it]?.job }
                if (!preemptible { jobs.joinAll() }) return null
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
                    if (!invokeCall(part.call.function, part.call.args.toEvaluated(), null, emptyMap())) return false
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

    private suspend fun invoke(call: StoryCall): Boolean {
        val evaluated = call.args.toEvaluated()
        val metadata = call.metadata.mapValues { (_, expr) -> evaluate(expr) }
        return invokeCall(call.function, evaluated, call.tag, metadata)
    }

    private suspend fun invokeCall(
        function: String,
        evaluated: EvaluatedArgs,
        tag: String?,
        metadata: Map<String, StoryValue>,
    ): Boolean {
        val resolved = functions.resolve(function, evaluated.positional, evaluated.named)
            ?: throw StoryRuntimeException("No overload of '@$function' accepts these arguments")
        val bound = bindActors(resolved.signature, evaluated.positional, evaluated.named)
        val args = StoryArguments.bind(resolved.signature, bound.positional, bound.named, metadata)
        val context = CallContext(this, callState)

        tag?.let { t ->
            startHandlers[t]?.forEach { it(StoryActionEvent(this, t, function, args)) }
            DialogueEvent.ActionStarted.post(DialogueEvent.ActionStarted(this, t, function, args))
        }
        var reason = StoryActionEnd.COMPLETED
        try {
            val completed = preemptible { resolved.handler.invoke(context, args) }
            if (!completed) reason = StoryActionEnd.CANCELLED
            return completed
        } catch (e: CancellationException) {
            reason = StoryActionEnd.CANCELLED
            throw e
        } catch (e: Throwable) {
            reason = StoryActionEnd.FAILED
            throw e
        } finally {
            if (reason == StoryActionEnd.COMPLETED) callState = CompoundTag()
            tag?.let { t ->
                endHandlers[t]?.forEach { it(StoryActionEndEvent(this, t, function, args, reason)) }
                DialogueEvent.ActionEnded.post(DialogueEvent.ActionEnded(this, t, function, args, reason))
            }
        }
    }

    private fun runCommand(command: String) {
        server.commands.performPrefixedCommand(commandSource(), command)
    }

    private fun commandSource() =
        server.createCommandSourceStack().withPermission(4).withSuppressedOutput()

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

    private suspend fun runTrack(address: String, start: Int, end: Int) {
        val program = programOf(address) ?: return
        var pc = start
        while (pc < end) {
            when (val instruction = program.instructions.getOrNull(pc)) {
                null -> return
                is StoryInstruction.Sync -> {
                    preemptions.send(pc + 1)
                    return
                }

                is StoryInstruction.Goto -> pc = instruction.target
                is StoryInstruction.GotoIfFalse ->
                    pc = if (evaluate(instruction.condition).isTruthy()) pc + 1 else instruction.target

                is StoryInstruction.Set -> {
                    variables[instruction.variable] = evaluate(instruction.value)
                    pc++
                }

                is StoryInstruction.Invoke -> {
                    val evaluated = instruction.call.args.toEvaluated()
                    val metadata = instruction.call.metadata.mapValues { (_, expr) -> evaluate(expr) }
                    invokeTracked(instruction.call.function, evaluated, instruction.call.tag, metadata)
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

    /** Same as [invokeCall] but without preemption, a track cannot preempt itself. */
    private suspend fun invokeTracked(
        function: String,
        evaluated: EvaluatedArgs,
        tag: String?,
        metadata: Map<String, StoryValue>,
    ) {
        val resolved = functions.resolve(function, evaluated.positional, evaluated.named)
            ?: throw StoryRuntimeException("No overload of '@$function' accepts these arguments")
        val bound = bindActors(resolved.signature, evaluated.positional, evaluated.named)
        val args = StoryArguments.bind(resolved.signature, bound.positional, bound.named, metadata)
        val context = CallContext(this, CompoundTag())
        tag?.let { t ->
            startHandlers[t]?.forEach { it(StoryActionEvent(this, t, function, args)) }
            DialogueEvent.ActionStarted.post(DialogueEvent.ActionStarted(this, t, function, args))
        }
        var reason = StoryActionEnd.COMPLETED
        try {
            resolved.handler.invoke(context, args)
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

    override fun cancelTrack(name: String) {
        tracks.remove(name)?.job?.cancel()
    }

    private fun cancelTracks() {
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

    private fun resolveCharacter(name: String): DialogueCharacter =
        boundCharacters[name] ?: StringCharacter(name)

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
        val program = programOf(destinationAddress)
            ?: throw StoryRuntimeException("Story '$destinationAddress' not found")
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

    private fun lookup(name: String): StoryValue? = variables[name] ?: resolveActor(name)

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

    private class CallContext(
        private val controller: DialogueController,
        override val state: CompoundTag,
    ) : StoryCallContext {
        override val session: DialogueSession get() = controller
        override val server: MinecraftServer get() = controller.server
        override val players: List<ServerPlayer> get() = controller.onlineParticipants

        override fun checkpoint() {
            controller.checkpointRequests.forEach { it(controller) }
        }
    }

    /** Callbacks that persist the session on demand, e.g. from a long-running function. */
    private val checkpointRequests = mutableListOf<(DialogueController) -> Unit>()

    /** Registers what to do when a story function asks for an immediate save. */
    fun onCheckpointRequest(handler: (DialogueController) -> Unit) {
        checkpointRequests += handler
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
