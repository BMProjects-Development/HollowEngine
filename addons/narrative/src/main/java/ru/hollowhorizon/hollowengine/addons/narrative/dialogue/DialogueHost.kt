package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

/**
 * What the story a dialogue plays in as a scene adds to it: saving after every statement, the journal of its
 * actions, checkpoints and rewinds. A dialogue played by itself has none of it.
 */
internal interface DialogueHost {
    /** Called before every statement, when [DialogueController.checkpoint] holds everything the previous ones did. */
    fun step()

    /**
     * Runs a reversible command of the main flow, given the statement it is as `hash#label+offset`: its journal record
     * is kept to finish the command after a restart instead of repeating it.
     */
    suspend fun journaled(statement: String, call: suspend () -> Unit)

    /** Runs [call] with the actions it performs taken back at the checkpoint [until], or at the next one when null. */
    suspend fun temporary(until: String?, call: suspend () -> Unit)

    /** Passes the checkpoint [name] of the story, with the dialogue standing after it. */
    fun checkpoint(name: String)

    /** Takes the story back to the checkpoint [name], or its latest one; the dialogue is stopped by it. */
    fun rewind(name: String?)
}
