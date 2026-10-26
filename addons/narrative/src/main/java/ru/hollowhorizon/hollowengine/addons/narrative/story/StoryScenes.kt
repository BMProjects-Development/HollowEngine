package ru.hollowhorizon.hollowengine.addons.narrative.story

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.DialogueCharacter
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.DialogueController
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.DialogueHost
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.DialogueResult
import ru.hollowhorizon.hollowengine.addons.narrative.story.SceneStage.KEY
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
        val controller = DialogueController(address) {
            locale = players.firstOrNull()?.clientInformation()?.language()
        }
        characters.forEach { (name, entity) -> controller.character(name, DialogueCharacter.of(entity)) }
        controller.outerVariables = RunVariables(frame.run)
        controller.load(CHECKPOINT, frame.locals)
        controller.host = SceneHost(frame, controller)

        val result = if (players.isEmpty()) controller.startHeadless() else controller.start(*players.toTypedArray())
        when (result) {
            is DialogueResult.Failed -> throw result.error as? StoryEntityMissing
                ?: StoryException("Scene '$address' failed: ${result.error.message}", result.error)

            DialogueResult.AllPlayersLeft -> throw StoryEntityMissing(players.first().uuid)
            else -> Unit
        }
    }

    /**
     * The story a scene plays in. Every statement of the scene is a segment of its frame: a restart repeats the
     * statement it was in, taking back the temporary actions that statement performed.
     */
    private class SceneHost(private val frame: StoryFrame, private val controller: DialogueController) : DialogueHost {
        override fun step() {
            controller.save(CHECKPOINT, frame.locals)
            frame.standAt(STATEMENT, calling = false)
            frame.segmentMark = frame.run.journal.next
        }

        /**
         * Calls the action of [statement] with its journal record kept in the frame, as a stage keeps the record of an
         * action it calls: after a restart an action the scene was in the middle of is finished, and one that was done
         * is not performed again.
         */
        override suspend fun journaled(statement: String, call: suspend () -> Unit) {
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

        override suspend fun temporary(until: String?, call: suspend () -> Unit) = temporaryUntil(until) { call() }

        override fun checkpoint(name: String) = frame.passCheckpoint(name)

        override fun rewind(name: String?) = frame.run.rewind(name)
    }

    /** The one site of a scene frame: the statement it is at. */
    private const val STATEMENT = "statement"

    /** The statement whose action [StoryFrame.action] records. */
    private const val ACTION_AT = "action_at"

    private fun playerOf(run: StoryRun, address: String): ServerPlayer = run.runs.owner.entity as? ServerPlayer
        ?: throw StoryException("scene(\"$address\") in story '${run.path}' needs with = players: the story does not belong to a player")
}
