package ru.hollowhorizon.hollowengine.addons.narrative.scene

/** Variables a dialogue shares with whoever plays it, such as the story a scene runs in. */
interface SceneVariables {
    /** The value of [name], or null when the variable is not one of these. */
    fun get(name: String): StoryValue?

    /** Writes [value] to [name] and returns true, or returns false when the variable is not one of these. */
    fun set(name: String, value: StoryValue): Boolean
}
