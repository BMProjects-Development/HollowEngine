package ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang

/**
 * A compiled story: a flat instruction list plus the label index. Nothing is reparsed while a
 * dialogue plays.
 *
 * [sourceHash] identifies the exact text this program came from. A session checkpoint stores it, and
 * a mismatch on resume is what triggers the rollback to label path.
 */
class StoryProgram(
    val address: String,
    val sourceHash: String,
    val instructions: List<StoryInstruction>,
    val labels: Map<String, Int>,
) {
    /** Label whose section contains [pc], i.e. the closest label at or before it. */
    fun labelAt(pc: Int): String? = instructions.getOrNull(pc)?.anchor?.label

    fun anchorOf(pc: Int): StoryAnchor =
        instructions.getOrNull(pc)?.anchor ?: StoryAnchor(null, pc, -1)

    /**
     * Finds the instruction matching [anchor] in this program: the label's section plus the recorded
     * offset. Returns null when the label is gone (or the offset runs past its section), which is the
     * caller's signal to restart the story.
     */
    fun resolve(anchor: StoryAnchor): Int? {
        val sectionStart = anchor.label?.let { labels[it] } ?: 0
        val pc = sectionStart + anchor.offset
        if (pc !in instructions.indices) return null
        if (instructions[pc].anchor.label != anchor.label) return null
        return pc
    }

    fun resolveLabelStart(anchor: StoryAnchor): Int? = anchor.label?.let { labels[it] }
}

/**
 * Position of an instruction, expressed the way a save can survive an edit: the label that opens its section.
 */
data class StoryAnchor(val label: String?, val offset: Int, val line: Int)

/** Target of `@jump`/`@call`: another file, another label, or both. */
data class StoryTarget(val address: String?, val label: String?, val span: StorySpan)

sealed interface StoryInstruction {
    val anchor: StoryAnchor

    /** Shows one line of dialogue and waits for the presenter to advance. */
    data class Say(
        val speaker: String?,
        val text: TextTemplate,
        override val anchor: StoryAnchor,
        val speakerExpr: StoryExpression? = null,
    ) : StoryInstruction

    /**
     * Shows every option of one `@choice` group at once and waits for the participants' decision.
     * [exit] is where the group ends, also where execution goes when no option is available.
     */
    data class Menu(
        val options: List<MenuOption>,
        val exit: Int,
        override val anchor: StoryAnchor,
    ) : StoryInstruction

    data class Set(val variable: String, val value: StoryExpression, override val anchor: StoryAnchor) : StoryInstruction

    /** Internal control flow produced by `@if`/`@while`/choice bodies. */
    data class Goto(val target: Int, override val anchor: StoryAnchor) : StoryInstruction

    data class GotoIfFalse(val condition: StoryExpression, val target: Int, override val anchor: StoryAnchor) : StoryInstruction

    /** `@jump` replaces the whole frame stack. */
    data class Jump(val target: StoryTarget, override val anchor: StoryAnchor) : StoryInstruction

    /** `@call` pushes a frame that `@return` (or the end of the section) pops. */
    data class Call(val target: StoryTarget, override val anchor: StoryAnchor) : StoryInstruction

    data class Return(override val anchor: StoryAnchor) : StoryInstruction

    /** A call into the function registry. */
    data class Invoke(val call: StoryCall, override val anchor: StoryAnchor) : StoryInstruction

    /** A vanilla command, run with server permissions anchored at the dialogue's actor. */
    data class Command(val text: TextTemplate, override val anchor: StoryAnchor) : StoryInstruction

    /**
     * Spawns a parallel track covering `[bodyStart, bodyEnd)` and continues the main flow at
     * [bodyEnd], the body sits inline in the instruction list.
     */
    data class AsyncStart(
        val trackName: String?,
        val bodyStart: Int,
        val bodyEnd: Int,
        override val anchor: StoryAnchor,
    ) : StoryInstruction

    /** `@await` waits for the named tracks, or for all of them when [trackNames] is empty. */
    data class Await(val trackNames: List<String>, override val anchor: StoryAnchor) : StoryInstruction

    data class Cancel(val trackName: String, override val anchor: StoryAnchor) : StoryInstruction

    /**
     * `@sync` is barrier-preempt. The track waits for the parent flow to reach a statement boundary,
     * then the rest of the track continues *as* the main flow.
     */
    data class Sync(override val anchor: StoryAnchor) : StoryInstruction
}

/** One button of a menu. [bodyStart] is where the branch begins; it ends by jumping past the group. */
data class MenuOption(
    val id: String?,
    val text: TextTemplate,
    val condition: StoryExpression?,
    val args: List<StoryArg>,
    val bodyStart: Int,
    val line: Int,
)

/**
 * A resolved function call: [args] are the parameters the signature declares, [metadata] holds named
 * parameters it does not, those reach event handlers untouched, which is how `tag=` and friends work
 * without every function declaring them.
 */
data class StoryCall(
    val function: String,
    val args: List<StoryArg>,
    val tag: String?,
    val metadata: Map<String, StoryExpression>,
    val span: StorySpan,
)
