package ru.hollowhorizon.hollowengine.addons.narrative.scene

import net.minecraft.server.MinecraftServer
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryAnchor

/**
 * The story a scene plays in: its server and variables, saving after every statement, the journal of its actions,
 * checkpoints, rewinds and the branches its tracks run as.
 */
internal interface SceneHost {
    val server: MinecraftServer

    /** The variables of the story, which the scene reads and writes before its own. */
    val variables: SceneVariables

    /** Called before every statement, when [ScenePlayer.checkpoint] holds everything the previous ones did. */
    fun step()

    /**
     * Runs a reversible command of the main flow, given the statement it is as `hash#label+offset`: its journal record
     * is kept to finish the command after a restart instead of repeating it. Returns what the command gave back.
     */
    suspend fun journaled(statement: String, call: suspend () -> Any?): Any?

    /** Runs [call] with the actions it performs taken back at the checkpoint [until], or at the next one when null. */
    suspend fun <T> temporary(until: String?, call: suspend () -> T): T

    /** Passes the checkpoint [name] of the story, with the dialogue standing after it. */
    fun checkpoint(name: String)

    /** Takes the story back to the checkpoint [name], or its latest one; the dialogue is stopped by it. */
    fun rewind(name: String?)

    /**
     * Plays the `@State` function [stage] of the running [script] with [positional] and [named] arguments, and
     * returns its result as a value of the dialogue, or null when it has none.
     */
    suspend fun stage(script: String, stage: String, positional: List<StoryValue>, named: Map<String, StoryValue>): StoryValue?

    /**
     * Starts the track of the `@async` at [anchor] in [address] as the branch [name] of the story, in place of one so
     * named. The branch keeps its place in the track across restarts and plays it through
     * [ScenePlayer.playTrack].
     */
    suspend fun startTrack(name: String, address: String, anchor: StoryAnchor)

    /** The tracks the dialogue started that the story still has, by name. */
    fun tracks(): Map<String, SceneTrack>
}

/** A track running as a branch of a story. */
internal interface SceneTrack {
    suspend fun join()

    fun cancel()
}
