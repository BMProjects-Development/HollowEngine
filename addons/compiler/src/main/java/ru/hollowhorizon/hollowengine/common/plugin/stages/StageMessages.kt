package ru.hollowhorizon.hollowengine.common.plugin.stages

/** Messages shared by front-end validation and the IR safety checks. */
internal object StageMessages {
    const val FUNCTION_VALUE = "A @State parameter needs a lambda written in place or another @State parameter; a regular function value has no saved stage"
    const val NESTED_CALL = "A stage cannot be called from a lambda inside a stage: its place could not be saved. Use async(name) { } or call it directly"
    const val HANDLER_CALL = "A stage cannot be called in catch or finally: keep what you need in a variable and call it after the try"
    const val CHECKPOINT_OUTSIDE = "checkpoint() must be called directly in a @State function"
    const val TOP_LEVEL = "A state function must be declared at the top level of a script"
    const val SUSPEND = "A state function must be a suspend function"
    const val TYPE_PARAMETERS = "A stage cannot have type parameters: its arguments are saved by their declared types"

    fun localClosure(description: String) =
        "A @State lambda cannot use $description declared outside it: its closure is not saved. Move it to the top level of the script and pass saved values as explicit arguments, or declare it inside the lambda"

    fun mutableCapture(name: String) = "A @State lambda cannot capture 'var $name'; copy it into a val first"

    fun assignCapture(name: String) = "A @State lambda cannot assign '$name': it keeps a saved copy"

    fun unsavedCapture(name: String, problem: String) =
        "A @State lambda saves what it captures, but '$name' cannot be saved: $problem"
}
