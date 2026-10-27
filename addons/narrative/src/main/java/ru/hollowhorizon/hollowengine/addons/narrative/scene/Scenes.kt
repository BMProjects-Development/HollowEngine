package ru.hollowhorizon.hollowengine.addons.narrative.scene

import ru.hollowhorizon.hollowengine.addons.narrative.story.ActionCommands

/**
 * Engine-wide scene state: the library that loads and caches stories, their commands being the registered actions
 * (see [ActionCommands]).
 */
object Scenes {
    val library = SceneLibrary { ActionCommands }

    init {
        // Stories live alongside scripts, so every script namespace is a story source too.
        library.addProvider(ScriptRegistryStorySource)
    }

    /** Registers a source of story text (a directory, an addon jar, an in-memory map). */
    fun addSource(provider: StorySourceProvider) = library.addProvider(provider)

    /** Drops every compiled story: the next load reads it again, checked against the actions registered by then. */
    fun reload() = library.invalidate()
}
