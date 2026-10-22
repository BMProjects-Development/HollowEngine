package ru.hollowhorizon.hollowengine.addons.narrative.story

/**
 * A stage: a suspend function whose place in a story survives a restart.
 *
 * Calling a stage returns to the caller like any other call, and the run keeps the whole call stack. Every
 * function on that stack continues after a restart from the last stage call or [checkpoint] it passed, with
 * its arguments and the locals it still needs restored. Arguments, the result and such locals must therefore be
 * serializable; entities are kept as references.
 *
 * On a function type, as in `block: @State suspend () -> Unit`, it makes the lambda written there a stage of
 * its own: what the lambda captures is saved as its arguments. [launchStory] and [async] take their bodies so.
 *
 * @param name the name saves refer to; the function name when empty. Set it to keep saves working after a rename.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
@Retention(AnnotationRetention.BINARY)
annotation class State(val name: String = "")

/**
 * Ends a segment of the enclosing stage: after a restart the stage continues from here rather than from its
 * previous stage call. Must be called directly in a `@State` function, and [name] must be unique within it.
 */
@Suppress("RedundantSuspendModifier")
suspend fun checkpoint(name: String): Unit =
    throw StoryException("checkpoint(\"$name\") must be called directly in a @State function")

/** Marks the entry the compiler generates for a stage. The runtime calls it to start or resume the stage by [name]. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class StageEntry(val name: String)

/**
 * Marks the call the compiler generates for a stage lambda: the runtime calls it with the captured values and the
 * parameters of a call, and the stage saves them all as its arguments.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class StageCall(val name: String)

/** Marks a script class that declares stages with the source [path] its stages are addressed by. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class StageScript(val path: String)

/** A story could not do what was asked of it. */
class StoryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
