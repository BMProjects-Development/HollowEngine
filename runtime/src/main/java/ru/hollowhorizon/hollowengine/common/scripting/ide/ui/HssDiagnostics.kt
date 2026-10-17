package ru.hollowhorizon.hollowengine.common.scripting.ide.ui

import ru.hollowhorizon.hollowengine.client.ui.style.HssDeclaration
import ru.hollowhorizon.hollowengine.client.ui.style.HssSchema
import ru.hollowhorizon.hollowengine.client.ui.style.HssValueKind
import ru.hollowhorizon.hollowengine.client.ui.style.HssVariableReference
import ru.hollowhorizon.hollowengine.client.ui.toStylePatch
import ru.hollowhorizon.hollowengine.common.scripting.ide.Diagnostic
import ru.hollowhorizon.hollowengine.common.scripting.ide.Position
import ru.hollowhorizon.hollowengine.common.scripting.ide.Range
import ru.hollowhorizon.hollowengine.common.scripting.ide.Severity

/**
 * Diagnostics for a stylesheet: syntax errors reported on the token they are about, plus
 * the semantic checks the schema makes possible unknown properties, values the compiler
 * would reject, and animations naming a `@keyframes` block that does not exist.
 */
internal fun hssDiagnostics(text: String): List<Diagnostic> {
    val model = HssDocumentModel(text)
    val offsets = LineOffsets(text)
    val diagnostics = ArrayList<Diagnostic>()

    for (error in model.errors) {
        diagnostics += offsets.diagnostic(error.position, error.endPosition, Severity.ERROR, error.messageText)
    }
    for ((import, reason) in model.scope.failures) {
        if (import.start >= 0) diagnostics += offsets.diagnostic(import.start, import.end, Severity.ERROR, reason)
    }
    for (variable in model.variables) {
        diagnostics += unknownVariables(variable.value, variable.valueStart, model, offsets)
    }
    for (declaration in model.declarations) {
        diagnostics += declarationDiagnostics(declaration, model, offsets)
    }
    return diagnostics.sortedWith(compareBy({ it.range.start.line }, { it.range.start.column }))
}

private fun declarationDiagnostics(
    declaration: HssDeclaration,
    model: HssDocumentModel,
    offsets: LineOffsets,
): List<Diagnostic> {
    val propertyRange = declaration.propertyRange ?: return emptyList()
    if (declaration.isApply) return unknownSet(declaration, model, offsets)
    val property = HssSchema.find(declaration.property)
        ?: return listOf(
            offsets.diagnostic(
                propertyRange.first,
                propertyRange.last + 1,
                Severity.WARNING,
                unknownPropertyMessage(declaration.property),
            ),
        )

    val valueRange = declaration.valueRange ?: return emptyList()
    val unknown = unknownVariables(declaration.value, declaration.valueStart, model, offsets)
    if (unknown.isNotEmpty()) return unknown
    val diagnostics = ArrayList<Diagnostic>()
    try {
        // Most properties parse their value lazily inside the patch writer, so the value is
        // only really checked once the modifier is applied to a patch.
        property.compile(model.scope.substitute(declaration.value).trim())?.let { listOf(it).toStylePatch() }
    } catch (exception: IllegalArgumentException) {
        diagnostics += offsets.diagnostic(
            valueRange.first,
            valueRange.last + 1,
            Severity.ERROR,
            exception.message ?: "Invalid value for '${property.name}'",
        )
    } catch (exception: NumberFormatException) {
        diagnostics += offsets.diagnostic(
            valueRange.first,
            valueRange.last + 1,
            Severity.ERROR,
            "Expected a number in '${declaration.value.trim()}'",
        )
    }
    diagnostics += keyframeDiagnostics(declaration, property.name, model, offsets)
    return diagnostics
}

/** `@apply $name;` naming a set that neither this stylesheet nor its imports declare. */
private fun unknownSet(declaration: HssDeclaration, model: HssDocumentModel, offsets: LineOffsets): List<Diagnostic> {
    val range = declaration.valueRange ?: return emptyList()
    val name = declaration.value.trim().removePrefix("$")
    if (name in model.scope.sets) return emptyList()
    return listOf(
        offsets.diagnostic(range.first, range.last + 1, Severity.ERROR, "No set '$$name' in this stylesheet or its imports"),
    )
}

/** Every `$name` in [value] that no variable in scope answers to. */
private fun unknownVariables(value: String, valueStart: Int, model: HssDocumentModel, offsets: LineOffsets): List<Diagnostic> {
    if (valueStart < 0) return emptyList()
    return HssVariableReference.findAll(value)
        .filter { it.groupValues[1] !in model.scope.variables }
        .map { match ->
            offsets.diagnostic(
                valueStart + match.range.first,
                valueStart + match.range.last + 1,
                Severity.ERROR,
                "No variable '${match.value}' in this stylesheet or its imports",
            )
        }.toList()
}

/** Flags `animations: fade …` when no `@keyframes fade` exists in the document. */
private fun keyframeDiagnostics(
    declaration: HssDeclaration,
    property: String,
    model: HssDocumentModel,
    offsets: LineOffsets,
): List<Diagnostic> {
    if (property != "animations" && property != "animation-name") return emptyList()
    val schema = HssSchema.find(property) ?: return emptyList()
    val valueStart = declaration.valueStart
    if (valueStart < 0 || declaration.value.equals("none", ignoreCase = true)) return emptyList()
    val diagnostics = ArrayList<Diagnostic>()
    for (entry in schema.entriesOf(declaration.value)) {
        for ((index, word) in entry.words.withIndex()) {
            val slot = schema.syntax.entry.slotAt(entry.words.map { it.text }, index) ?: continue
            if (slot.kind != HssValueKind.KEYFRAMES) continue
            val name = word.text.trim('"', '\'')
            if (name.isEmpty() || name in model.keyframeNames) continue
            diagnostics += offsets.diagnostic(
                valueStart + word.start,
                valueStart + word.end,
                Severity.WARNING,
                "No '@keyframes $name' in this stylesheet",
            )
        }
    }
    return diagnostics
}

private fun unknownPropertyMessage(property: String): String {
    val suggestion = HssSchema.allNames.minByOrNull { editDistance(property.lowercase(), it) }
        ?.takeIf { editDistance(property.lowercase(), it) <= property.length / 2 + 1 }
    return if (suggestion == null) {
        "Unknown property '$property'"
    } else {
        "Unknown property '$property'; did you mean '$suggestion'?"
    }
}

/** Small Levenshtein distance, used only to suggest a property name on a typo. */
private fun editDistance(left: String, right: String): Int {
    if (left == right) return 0
    var previous = IntArray(right.length + 1) { it }
    var current = IntArray(right.length + 1)
    for (i in 1..left.length) {
        current[0] = i
        for (j in 1..right.length) {
            val substitution = previous[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1
            current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[right.length]
}

/** Offset-to-position mapping computed once per diagnostics pass. */
private class LineOffsets(text: String) {
    private val starts = buildList {
        add(0)
        text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
    }

    fun diagnostic(start: Int, end: Int, severity: Severity, message: String): Diagnostic =
        Diagnostic(Range(position(start), position(maxOf(end, start + 1))), severity, message)

    private fun position(offset: Int): Position {
        val line = starts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
        return Position(line, offset - starts[line])
    }
}
