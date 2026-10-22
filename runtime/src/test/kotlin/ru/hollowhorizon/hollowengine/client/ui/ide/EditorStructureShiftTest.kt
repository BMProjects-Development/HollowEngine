package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.common.scripting.ide.EditorStructure
import ru.hollowhorizon.hollowengine.common.scripting.ide.GutterMarker
import ru.hollowhorizon.hollowengine.common.scripting.ide.GutterMarkerKind
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptRunKind
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptRunTarget
import kotlin.test.Test
import kotlin.test.assertEquals

/** The gutter keeps its markers between an edit and the analysis of the edited text. */
class EditorStructureShiftTest {
    private val original = "fun first() {}\n@State suspend fun second() {}\nfun third() {}"
    private val structure = EditorStructure(
        markers = listOf(
            GutterMarker(original.indexOf("first"), GutterMarkerKind.EVENT),
            GutterMarker(original.indexOf("third"), GutterMarkerKind.OVERRIDE),
        ),
        runTargets = listOf(ScriptRunTarget(original.indexOf("second"), ScriptRunKind.NODE, "second")),
    )

    @Test
    fun `markers before and after an edit move with the text`() {
        val edited = original.replace("fun first() {}", "fun first() { work() }")
        val shifted = shiftEditorStructureThroughEdit(original, edited, structure)
        assertEquals(listOf(edited.indexOf("first"), edited.indexOf("third")), shifted.markers.map { it.offset })
        assertEquals(edited.indexOf("second"), shifted.runTargets.single().offset)
    }

    @Test
    fun `a marker the edit went through waits for the next analysis`() {
        val edited = original.replace("fun third", "fun rd")
        val shifted = shiftEditorStructureThroughEdit(original, edited, structure)
        assertEquals(listOf(GutterMarkerKind.EVENT), shifted.markers.map { it.kind })
        assertEquals(1, shifted.runTargets.size)
    }
}
