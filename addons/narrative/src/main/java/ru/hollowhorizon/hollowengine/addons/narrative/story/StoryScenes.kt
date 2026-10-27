package ru.hollowhorizon.hollowengine.addons.narrative.story

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.addons.narrative.scene.*
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryAnchor
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryType
import ru.hollowhorizon.hollowengine.addons.narrative.story.SceneStage.KEY
import kotlin.reflect.KParameter
import ru.hollowhorizon.hollowengine.addons.narrative.story.temporary as temporaryUntil

/**
 * Plays the scene [address] as a stage of the running story and returns when it ends. After a restart the scene
 * goes on from the statement it was at.
 *
 * The scene reads and writes the [saved] variables of the scripts the story runs in by their names, `trust` for
 * `var trust by saved(0)`; a name it sets that is not one of them stays in the scene.
 *
 * @param with the players who see the scene and vote in its choices; the player a story of a player belongs to when
 * null. Empty plays the scene to nobody.
 * @param characters the entities the speaker names of the scene stand for.
 */
@State
suspend fun scene(
    address: String,
    with: List<ServerPlayer>? = null,
    characters: Map<String, LivingEntity> = emptyMap(),
) = SceneStage.play(address, with, characters)

/** The stage behind [scene]. Stories address it by [KEY], which saves keep. */
internal object SceneStage {
    const val KEY = "hollowengine-narrative:scene"
    private const val STAGE = "scene"

    /** Where the frame keeps the checkpoint of the scene. */
    private const val CHECKPOINT = "scene"

    private val ADDRESS = storySerializer<String>()
    private val PLAYERS = storySerializer<List<ServerPlayer>>()
    private val CHARACTERS = storySerializer<Map<String, LivingEntity>>()

    @Suppress("UNCHECKED_CAST")
    @StageEntry(STAGE)
    suspend fun entry(args: CompoundTag): Any {
        play(
            StoryValues.get(args, "address", ADDRESS) as String,
            StoryValues.get(args, "with", PLAYERS) as List<ServerPlayer>,
            StoryValues.get(args, "characters", CHARACTERS) as Map<String, LivingEntity>,
        )
        return Unit
    }

    suspend fun play(address: String, with: List<ServerPlayer>?, characters: Map<String, LivingEntity>) {
        val frame = StoryFrames.enter(this, STAGE, 0, arrayOf(STATEMENT))
        try {
            // Saved as resolved: NBT reads an empty nullable list back as null.
            val players = with ?: listOf(playerOf(frame.run, address))
            frame.saveArg("address", ADDRESS, address)
            frame.saveArg("with", PLAYERS, players)
            frame.saveArg("characters", CHARACTERS, characters)
            perform(frame, address, players, characters)
        } catch (failure: Throwable) {
            frame.abort(failure)
            throw failure
        } finally {
            frame.leave()
        }
    }

    private suspend fun perform(
        frame: StoryFrame,
        address: String,
        players: List<ServerPlayer>,
        characters: Map<String, LivingEntity>,
    ) {
        val locale = players.firstOrNull()?.clientInformation()?.language()
        val player = ScenePlayer(address, Scenes.library, locale = locale)
        characters.forEach { (name, entity) -> player.character(name, DialogueCharacter.of(entity)) }
        player.host = StorySceneHost(frame, player)
        if (frame.locals.contains(CHECKPOINT)) player.restore(SceneCheckpoint.load(frame.locals.getCompound(CHECKPOINT)))

        SceneTracks.attach(frame.run, player)
        val result = try {
            player.play(players)
        } finally {
            SceneTracks.detach(frame.run)
        }
        when (result) {
            is SceneResult.Failed -> throw result.error as? StoryEntityMissing
                ?: StoryException("Scene '$address' failed: ${result.error.message}", result.error)

            SceneResult.AllPlayersLeft -> throw StoryEntityMissing(players.first().uuid)
            else -> Unit
        }
    }

    /**
     * The story a scene plays in. Every statement of the scene is a segment of its frame: a restart repeats the
     * statement it was in, taking back the temporary actions that statement performed.
     */
    private class StorySceneHost(private val frame: StoryFrame, private val player: ScenePlayer) : SceneHost {
        override val server get() = frame.run.runs.owner.server

        override val variables = RunVariables(frame.run)

        override fun step() {
            val checkpoint = player.checkpoint()
            if (checkpoint == null) frame.locals.remove(CHECKPOINT) else frame.locals.put(CHECKPOINT, checkpoint.save())
            frame.standAt(STATEMENT, calling = false)
            frame.segmentMark = frame.run.journal.next
        }

        override suspend fun journaled(statement: String, call: suspend () -> Unit) = journaled(frame, statement, call)

        override suspend fun startTrack(name: String, address: String, anchor: StoryAnchor) =
            SceneTracks.start(frame, name, address, anchor)

        override fun tracks(): Map<String, SceneTrack> = SceneTracks.of(frame)

        override suspend fun temporary(until: String?, call: suspend () -> Unit) = temporaryUntil(until) { call() }

        override fun checkpoint(name: String) = frame.passCheckpoint(name)

        /**
         * Calls the stage as a stage calls another, at the one site of the frame: after a restart in the middle the
         * saved stage goes on, and the scene does not call it again.
         */
        override suspend fun stage(
            script: String,
            stage: String,
            positional: List<StoryValue>,
            named: Map<String, StoryValue>,
        ): StoryValue? {
            if (frame.isResumingAt(CALL_POSITION) && frame.run.pending.isNotEmpty()) {
                return StoryConversions.toStory(frame.resumeCall(0))
            }
            val key = StoryInstances.scriptNamed(script)
            val parameters =
                StoryInstances.stageFunction(key, stage).parameters.filter { it.kind != KParameter.Kind.INSTANCE }
            val args = CompoundTag()
            parameters.forEachIndexed { index, parameter ->
                val name = if (parameter.kind == KParameter.Kind.EXTENSION_RECEIVER) "this" else parameter.name
                    ?: "argument$index"
                val written = named[name] ?: positional.getOrNull(index)
                ?: throw StoryException("'@stage $script#$stage' needs '$name'")
                val value =
                    (written as? StoryString)?.takeIf { StoryConversions.typeOf(parameter.type) == StoryType.ACTOR }
                        ?.let { player.resolveActor(it.value) } ?: written
                StoryValues.put(
                    args,
                    name,
                    StoryValues.serializer(parameter.type),
                    StoryConversions.convert(value, parameter.type, name)
                )
            }
            frame.beginCall(0)
            return StoryConversions.toStory(frame.completeCall(0, StoryInstances.invoke(key, stage, args)))
        }

        override fun rewind(name: String?) = frame.run.rewind(name)
    }

    /**
     * Calls the action of [statement] with its journal record kept in [frame], as a stage keeps the record of an
     * action it calls: after a restart an action the scene or track was in the middle of is finished, and one that
     * was done is not performed again.
     */
    internal suspend fun journaled(frame: StoryFrame, statement: String, call: suspend () -> Unit) {
        val interrupted =
            frame.action?.takeIf { it != StoryFrame.NO_RECORD && frame.locals.getString(ACTION_AT) == statement }
        val entry = interrupted?.let(frame.run.journal::entry)
        frame.standAt(STATEMENT, calling = true)
        try {
            when {
                entry?.done == true -> Unit
                entry != null -> StoryActions.resume(entry)
                else -> {
                    frame.action = StoryFrame.NO_RECORD
                    frame.locals.putString(ACTION_AT, statement)
                    call()
                }
            }
        } finally {
            if (!frame.run.isCancelled) {
                frame.action = null
                frame.locals.remove(ACTION_AT)
                frame.standAt(STATEMENT, calling = false)
            }
        }
    }

    /** The one site of a scene frame: the statement it is at. */
    internal const val STATEMENT = "statement"

    /** The position of a frame in the middle of the call at its first site. */
    private const val CALL_POSITION = 0

    /** The statement whose action [StoryFrame.action] records. */
    private const val ACTION_AT = "action_at"

    private fun playerOf(run: StoryRun, address: String): ServerPlayer = run.runs.owner.entity as? ServerPlayer
        ?: throw StoryException("scene(\"$address\") in story '${run.path}' needs with = players: the story does not belong to a player")
}
