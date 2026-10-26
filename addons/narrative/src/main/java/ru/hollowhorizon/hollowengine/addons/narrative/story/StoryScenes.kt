package ru.hollowhorizon.hollowengine.addons.narrative.story

import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.DialogueCharacter
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.DialogueController
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.DialogueResult
import ru.hollowhorizon.hollowengine.addons.narrative.story.SceneStage.KEY

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
        val frame = StoryFrames.enter(this, STAGE, 0, emptyArray())
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
        controller.onStep = { controller.save(CHECKPOINT, frame.locals) }

        val result = if (players.isEmpty()) controller.startHeadless() else controller.start(*players.toTypedArray())
        when (result) {
            is DialogueResult.Failed -> throw result.error as? StoryEntityMissing
                ?: StoryException("Scene '$address' failed: ${result.error.message}", result.error)
            DialogueResult.AllPlayersLeft -> throw StoryEntityMissing(players.first().uuid)
            else -> Unit
        }
    }

    private fun playerOf(run: StoryRun, address: String): ServerPlayer = run.runs.owner.entity as? ServerPlayer
        ?: throw StoryException("scene(\"$address\") in story '${run.path}' needs with = players: the story does not belong to a player")
}
