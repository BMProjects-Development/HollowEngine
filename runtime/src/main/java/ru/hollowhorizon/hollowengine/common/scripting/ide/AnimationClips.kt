package ru.hollowhorizon.hollowengine.common.scripting.ide

/** What the analyzer asks of the game to check names of clips. */
interface AnimationClips {
    /** The clips of [model], or null while it loads or when it cannot be read. */
    fun of(model: String): List<String>?

    /** The model the NPC of the world with the author's [id] shows, or null while that is not known. */
    fun modelOfNpc(id: String): String?

    object None : AnimationClips {
        override fun of(model: String): List<String>? = null
        override fun modelOfNpc(id: String): String? = null
    }
}
