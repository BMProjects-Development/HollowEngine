package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import ru.hollowhorizon.hollowengine.addons.narrative.story.ActionCommands

/**
 * Builds a [DialogueController]:
 *
 * ```kotlin
 * val controller = DialogueController("project:stories/example.story") {
 *     locale = "ru_ru"
 * }
 * ```
 *
 * The commands a story calls are the registered actions, see [ActionCommands].
 */
class DialogueControllerBuilder(private val address: String) {
    var presentation: DialoguePresenter = StoryEngine.presentation()

    var library: StoryLibrary = StoryEngine.library

    var votePolicy: VotePolicy = VotePolicy()

    /** Locale to look for, e.g. `ru_ru`; null plays the base story. */
    var locale: String? = null

    internal fun build() = DialogueController(address, presentation, library, votePolicy, locale)
}

fun DialogueController(address: String, block: DialogueControllerBuilder.() -> Unit = {}): DialogueController =
    DialogueControllerBuilder(address).apply(block).build()

/** Engine-wide dialogue state: the library that loads and caches stories, and the default presentation. */
object StoryEngine {
    val library = StoryLibrary { ActionCommands }

    /** Makes the presentation of a dialogue that does not choose its own, scenes of stories included. */
    var presentation: () -> DialoguePresenter = { UiDialoguePresentation() }

    init {
        // Stories live alongside scripts, so every script namespace is a story source too.
        library.addProvider(ScriptRegistryStorySource)
    }

    /** Registers a source of story text (a directory, an addon jar, an in-memory map). */
    fun addSource(provider: StorySourceProvider) = library.addProvider(provider)

    /** Drops every compiled story: the next load reads it again, checked against the actions registered by then. */
    fun reload() = library.invalidate()
}
