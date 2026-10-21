package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

/**
 * Builds a [DialogueController]:
 *
 * ```kotlin
 * val controller = DialogueController("project:stories/example.story") {
 *     functions.function<String, Float>("play-video", ["name", "volume"]) { name, volume -> … }
 * }
 * ```
 *
 * The registry starts as a child of [StoryEngine.functions], so a dialogue sees every built-in
 * function plus whatever it adds itself, without those additions leaking into other dialogues.
 */
class DialogueControllerBuilder(private val address: String) {
    var presentation: DialoguePresenter = UiDialoguePresentation()

    /** Functions this dialogue may call, on top of the engine-wide ones. */
    val functions: StoryFunctionRegistry = StoryFunctionRegistry(StoryEngine.functions)

    var library: StoryLibrary = StoryEngine.library

    var votePolicy: VotePolicy = VotePolicy()

    /** Locale to look for, e.g. `ru_ru`; null plays the base story. */
    var locale: String? = null

    internal fun build() = DialogueController(address, presentation, functions, library, votePolicy, locale)
}

fun DialogueController(address: String, block: DialogueControllerBuilder.() -> Unit = {}): DialogueController =
    DialogueControllerBuilder(address).apply(block).build()

/**
 * Engine-wide dialogue state: the function registry every dialogue inherits and the library that
 * loads and caches stories. Addons register their functions here.
 */
object StoryEngine {
    val functions = StoryFunctionRegistry()

    val library = StoryLibrary { functions }

    init {
        prepareReload()
        postRegistrationEvent()
        // Stories live alongside scripts, so every script namespace is a story source too.
        library.addProvider(ScriptRegistryStorySource)
    }

    /** Registers a source of story text (a directory, an addon jar, an in-memory map). */
    fun addSource(provider: StorySourceProvider) = library.addProvider(provider)

    /**
     * Drops every compiled story and lets addons and reload scripts register their functions again.
     * The next load re-reads from disk.
     */
    fun reload() {
        prepareReload()
        completeReload()
    }

    /** Reinstalls engine-owned functions before reload scripts replace their event handlers. */
    internal fun prepareReload() {
        StoryBuiltinFunctions.install(functions)
        StoryNpcFunctions.install(functions)
        StoryEffectFunctions.install(functions)
    }

    /** Publishes the rebuilt registry after reload scripts have installed their new handlers. */
    internal fun completeReload() {
        postRegistrationEvent()
        library.invalidate()
    }

    private fun postRegistrationEvent() {
        RegisterStoryFunctionsEvent.post(RegisterStoryFunctionsEvent(functions))
    }
}
