package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.withContext
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.addons.narrative.scene.commands.ScreenEffects
import ru.hollowhorizon.hollowengine.addons.narrative.speech.StoryAudience
import ru.hollowhorizon.hollowengine.addons.narrative.story.SceneStage.KEY

/**
 * Plays the story file [address] as a stage of the running story and returns when it ends. After a restart it goes on
 * from the step it was at.
 *
 * @param address `path/name.story`, or `path/name.story#stage` for another start than `main`.
 * @param with the players who see it and vote in its choices; the player a story of a player belongs to when null.
 * Empty plays it to nobody.
 */
@State
suspend fun scene(address: String, with: List<ServerPlayer>? = null) = SceneStage.play(address, with)

/** The stage behind [scene]. Stories address it by [KEY], which saves keep. */
internal object SceneStage {
    const val KEY = "hollowengine-narrative:scene"
    private const val STAGE = "scene"
    private const val CALL = "call"

    private val ADDRESS = storySerializer<String>()
    private val PLAYERS = storySerializer<List<ServerPlayer>>()

    @Suppress("UNCHECKED_CAST")
    @StageEntry(STAGE)
    suspend fun entry(args: CompoundTag): Any {
        play(StoryValues.get(args, "address", ADDRESS) as String, StoryValues.get(args, "with", PLAYERS) as List<ServerPlayer>)
        return Unit
    }

    /** The file [address] played to [with], as a stage a run can start with. */
    fun lambda(address: String, with: List<ServerPlayer>): StageLambda0<Unit> {
        val args = CompoundTag()
        StoryValues.put(args, "address", ADDRESS, address)
        StoryValues.put(args, "with", PLAYERS, with)
        return StageLambda0(KEY, STAGE, args)
    }

    suspend fun play(address: String, with: List<ServerPlayer>?) {
        val frame = StoryFrames.enter(this, STAGE, 0, arrayOf(CALL))
        try {
            // Saved as resolved: NBT reads an empty nullable list back as null.
            val players = with ?: listOf(playerOf(frame.run, address))
            frame.saveArg("address", ADDRESS, address)
            frame.saveArg("with", PLAYERS, players)
            val audience = StoryAudience(players)
            try {
                withContext(audience) { perform(frame, address) }
            } finally {
                ScreenEffects.giveBack(audience)
            }
        } catch (failure: Throwable) {
            frame.abort(failure)
            throw failure
        } finally {
            frame.leave()
        }
    }

    /** Calls the stage as a stage calls another: after a restart the saved one goes on instead of a new one. */
    private suspend fun perform(frame: StoryFrame, address: String) {
        if (frame.isResumingAt(0) && frame.run.pending.isNotEmpty()) {
            frame.resumeCall(0)
            return
        }
        val (key, stage) = StoryScripts.resolve(address)
        frame.beginCall(0)
        frame.completeCall(0, StoryInstances.invoke(key, stage, CompoundTag()))
    }

    private fun playerOf(run: StoryRun, address: String): ServerPlayer = run.runs.owner.entity as? ServerPlayer
        ?: throw StoryException("scene(\"$address\") in story '${run.path}' needs with = players: the story does not belong to a player")
}
