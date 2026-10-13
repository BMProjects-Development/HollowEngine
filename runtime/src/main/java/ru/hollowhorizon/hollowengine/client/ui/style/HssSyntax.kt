package ru.hollowhorizon.hollowengine.client.ui.style

/**
 * Kind of a single value token. Drives value completion, the labels shown as inlay hints
 * and the signature rendered next to a property name in the IDE, so a property never has
 * to describe its value shape twice.
 */
enum class HssValueKind(val display: String, val candidates: List<String> = emptyList()) {
    LENGTH("length", listOf("0px", "4px", "8px", "16px", "50%", "100%")),

    /** A length the engine resolves eagerly, so percentages are not accepted. */
    PIXELS("px", listOf("0px", "2px", "4px", "8px", "16px")),
    NUMBER("number", listOf("0", "0.5", "1")),
    INTEGER("int", listOf("0", "1", "2")),
    COLOR("color", listOf("#FFFFFF", "#000000", "transparent", "white", "black", "rgba(255, 255, 255, 1)", "alpha(#FFFFFF, 0.5)")),
    PAINT(
        "paint",
        listOf(
            "#FFFFFF",
            "transparent",
            "none",
            "image(\"\")",
            "linear-gradient(to left, #000000, #FFFFFF)",
            "radial-gradient(#000000, #FFFFFF)",
        ),
    ),
    BOOLEAN("boolean", listOf("true", "false")),
    DURATION("time", listOf("0ms", "100ms", "200ms", "1s")),
    EASING(
        "easing",
        listOf("linear", "ease", "ease-in", "ease-out", "ease-in-out", "steps(3)", "cubic-bezier(0.4, 0, 0.2, 1)"),
    ),
    KEYWORD("keyword"),
    RESOURCE("resource", listOf("image(\"\")", "url(\"\")")),
    SHAPE("shape", listOf("path(\"M 0 0 L 10 0 L 10 10 Z\", 10 10)", "svg(\"hollowengine:ui/shapes/hexagon.svg\")")),
    FILTER("filter", listOf("none", "blur(4px)", "grayscale(1)")),
    TEXT_EFFECT("effect", listOf("bold", "italic", "underline", "shadow(1, 1, 0, #000000)", "gradient(#FF0000, #0000FF)")),

    /** Name of a `@keyframes` block; the IDE completes it from the edited document. */
    KEYFRAMES("keyframes"),

    /** Name of a style property, as accepted by `transitions`. */
    STYLE_PROPERTY("property"),
    TEXT("text"),
    ANY("value"),
}

/**
 * One positional element of a declaration value: `margin: <top> <right> <bottom> <left>`
 * is four slots, `text-align: left|center|right` is a single keyword slot.
 */
data class HssSlot(
    val name: String,
    val kind: HssValueKind,
    val optional: Boolean = false,
    /** Literal words accepted in addition to (or instead of) the kind's own candidates. */
    val keywords: List<String> = emptyList(),
) {
    /** Extra completion candidates offered for this slot, most specific first. */
    fun candidates(): List<String> = (keywords + kind.candidates).distinct()

    fun signature(): String {
        val body = if (kind == HssValueKind.KEYWORD && keywords.size <= KeywordInlineLimit && keywords.isNotEmpty()) {
            keywords.joinToString("|")
        } else {
            "<$name>"
        }
        return if (optional) "[$body]" else body
    }

    private companion object {
        const val KeywordInlineLimit = 4
    }
}

/** How fewer tokens than slots are spread over the slots. */
enum class HssFold {
    /** Token *i* is slot *i*; missing trailing tokens fall back to their defaults. */
    POSITIONAL,

    /** CSS edge shorthand: `8px`, `8px 12px`, `8px 12px 4px`, `8px 12px 4px 12px`. */
    EDGES,

    /** A single token covers both axes: `size: 64px` is `64px 64px`. */
    AXES,
}

/**
 * Grammar of one entry of a declaration value. Entries know which slot each token binds
 * to, which is what makes `margin: 8px 8px 60px 8px` readable in the editor.
 */
class HssEntry internal constructor(
    val slots: List<HssSlot>,
    val fold: HssFold = HssFold.POSITIONAL,
    /** Character between the slots of one entry; `null` means whitespace. */
    val wordSeparator: Char? = null,
    /** Order-free values (`animations`, `shadows`) classify tokens by shape instead of position. */
    private val classifier: ((tokens: List<String>, index: Int) -> HssSlot?)? = null,
) {
    fun signature(): String = slots.joinToString(if (wordSeparator == null) " " else "$wordSeparator ") {
        it.signature()
    }

    fun slotAt(tokens: List<String>, index: Int): HssSlot? {
        classifier?.let { return it(tokens, index) }
        if (index !in tokens.indices) return slots.getOrNull(index)
        return when (fold) {
            HssFold.POSITIONAL -> slots.getOrNull(index)
            HssFold.AXES -> if (tokens.size == 1) slots.firstOrNull() else slots.getOrNull(index)
            HssFold.EDGES -> slots.getOrNull(edgeSlotIndex(tokens.size, index))
        }
    }

    /** Inlay label for token [index], or `null` when the token speaks for itself. */
    fun labelAt(tokens: List<String>, index: Int): String? {
        if (classifier != null || fold == HssFold.POSITIONAL) return slotAt(tokens, index)?.name
        return when (fold) {
            HssFold.AXES -> if (tokens.size == 1) axesLabel() else slots.getOrNull(index)?.name
            HssFold.EDGES -> edgeLabel(tokens.size, index)
            HssFold.POSITIONAL -> slotAt(tokens, index)?.name
        }
    }

    private fun axesLabel(): String? {
        val first = slots.getOrNull(0)?.name ?: return null
        val second = slots.getOrNull(1)?.name ?: return first
        return "$first/$second"
    }

    private fun edgeLabel(count: Int, index: Int): String? = when (count) {
        1 -> "all"
        2 -> when (index) {
            0 -> pairLabel(0, 2)
            else -> pairLabel(3, 1)
        }

        3 -> when (index) {
            1 -> pairLabel(3, 1)
            else -> slots.getOrNull(edgeSlotIndex(count, index))?.name
        }

        else -> slots.getOrNull(edgeSlotIndex(count, index))?.name
    }

    private fun pairLabel(first: Int, second: Int): String? {
        val start = slots.getOrNull(first)?.name ?: return null
        val end = slots.getOrNull(second)?.name ?: return start
        return "$start/$end"
    }

    /** Slot a token binds to under the CSS edge shorthand, matching `parseInsets`. */
    private fun edgeSlotIndex(count: Int, index: Int): Int = when (count) {
        1 -> 0
        2 -> if (index == 0) 0 else 1
        3 -> index
        else -> index
    }
}

/**
 * Grammar of a whole declaration value: one entry, or a comma-separated list of entries
 * for the plural properties (`transitions`, `animations`, `shadows`, `text-effects`).
 */
class HssSyntax internal constructor(
    val entry: HssEntry,
    val repeated: Boolean = false,
) {
    val slots: List<HssSlot> get() = entry.slots

    fun signature(): String = if (repeated) "${entry.signature()}, ..." else entry.signature()
}

/** Builds a single-entry syntax from [slots]. */
internal fun syntax(vararg slots: HssSlot, fold: HssFold = HssFold.POSITIONAL): HssSyntax =
    HssSyntax(HssEntry(slots.toList(), fold))

/** Builds a single-entry syntax whose slots are separated by commas, not whitespace. */
internal fun commaSyntax(vararg slots: HssSlot): HssSyntax = HssSyntax(HssEntry(slots.toList(), wordSeparator = ','))

/** Builds a comma-separated list syntax whose entries follow [slots]. */
internal fun listSyntax(
    vararg slots: HssSlot,
    classifier: ((tokens: List<String>, index: Int) -> HssSlot?)? = null,
): HssSyntax = HssSyntax(HssEntry(slots.toList(), classifier = classifier), repeated = true)

internal fun slot(
    name: String,
    kind: HssValueKind,
    optional: Boolean = false,
    keywords: List<String> = emptyList(),
): HssSlot = HssSlot(name, kind, optional, keywords)

/** `<length>` slot that also accepts the layout keywords. */
internal fun sizeSlot(name: String, auto: Boolean = true): HssSlot = HssSlot(
    name,
    HssValueKind.LENGTH,
    keywords = if (auto) listOf("fill", "fit", "auto") else emptyList(),
)

internal fun keywordSlot(name: String, vararg keywords: String): HssSlot =
    HssSlot(name, HssValueKind.KEYWORD, keywords = keywords.toList())

/** Four-edge shorthand (`padding`, `margin`, `image-slice`). */
internal fun edgesSyntax(auto: Boolean): HssSyntax = HssSyntax(
    HssEntry(
        listOf(sizeSlot("top", auto), sizeSlot("right", auto), sizeSlot("bottom", auto), sizeSlot("left", auto)),
        HssFold.EDGES,
    ),
)

/** Two-axis shorthand (`size`, `align`). */
internal fun axesSyntax(first: HssSlot, second: HssSlot): HssSyntax =
    HssSyntax(HssEntry(listOf(first, second), HssFold.AXES))
