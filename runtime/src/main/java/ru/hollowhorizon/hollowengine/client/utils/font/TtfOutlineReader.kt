package ru.hollowhorizon.hollowengine.client.utils.font

import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.freetype.*
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Reads glyph outlines out of a TrueType/OpenType face using the FreeType.
 */
internal class TtfFace private constructor(
    private val library: Long,
    private val face: FT_Face,
    private val fontData: ByteBuffer,
) : AutoCloseable {
    val unitsPerEm: Float = face.units_per_EM().toFloat().takeIf { it > 0f } ?: DefaultUnitsPerEm
    val ascender: Float get() = face.ascender() / unitsPerEm
    val descender: Float get() = face.descender() / unitsPerEm
    val lineHeight: Float get() = face.height() / unitsPerEm
    val underlineY: Float get() = face.underline_position() / unitsPerEm
    val underlineThickness: Float get() = face.underline_thickness() / unitsPerEm
    val familyName: String get() = runCatching { face.family_nameString() }.getOrNull() ?: "TrueType"

    private val decomposer = OutlineDecomposer()

    fun hasGlyph(codepoint: Int): Boolean = FreeType.FT_Get_Char_Index(face, codepoint.toLong()) != 0

    /**
     * The pen advance for [codepoint] in em, without building its outline. Null when the face has
     * no glyph for it.
     */
    fun advanceOf(codepoint: Int): Float? {
        val index = FreeType.FT_Get_Char_Index(face, codepoint.toLong())
        if (index == 0) return null
        val loadFlags = FreeType.FT_LOAD_NO_SCALE or FreeType.FT_LOAD_NO_HINTING or
                FreeType.FT_LOAD_NO_BITMAP or FreeType.FT_LOAD_LINEAR_DESIGN
        if (FreeType.FT_Load_Glyph(face, index, loadFlags) != 0) return null
        val slot = face.glyph() ?: return null
        return slot.metrics().horiAdvance().toFloat() / unitsPerEm
    }

    /**
     * Loads [codepoint] and returns its advance in em plus its outline, flattened densely enough
     * that the chord error stays well under a pixel at [pixelsPerEm]. Null when the face has no
     * glyph for the codepoint; a covered but inkless glyph (a space) comes back with an empty shape.
     */
    fun loadGlyph(codepoint: Int, pixelsPerEm: Float): TtfGlyphOutline? {
        val index = FreeType.FT_Get_Char_Index(face, codepoint.toLong())
        if (index == 0) return null
        val loadFlags = FreeType.FT_LOAD_NO_SCALE or FreeType.FT_LOAD_NO_HINTING or FreeType.FT_LOAD_NO_BITMAP
        if (FreeType.FT_Load_Glyph(face, index, loadFlags) != 0) return null
        val slot = face.glyph() ?: return null
        val advance = slot.metrics().horiAdvance().toFloat() / unitsPerEm
        val outline = slot.outline()
        if (outline.n_contours().toInt() <= 0) return TtfGlyphOutline(advance, MsdfShape())
        val shape = decomposer.decompose(outline, pixelsPerEm / unitsPerEm)
        return TtfGlyphOutline(advance, shape)
    }

    override fun close() {
        decomposer.close()
        FreeType.FT_Done_Face(face)
        FreeType.FT_Done_FreeType(library)
        MemoryUtil.memFree(fontData)
    }

    companion object {
        private const val DefaultUnitsPerEm = 1000f

        /**
         * FreeType references the font bytes rather than copying them, so they live off-heap for as
         * long as the face does and are freed together with it.
         */
        fun open(bytes: ByteArray): TtfFace {
            val buffer = MemoryUtil.memAlloc(bytes.size)
            buffer.put(bytes)
            buffer.flip()
            var library = MemoryUtil.NULL
            try {
                MemoryStack.stackPush().use { stack ->
                    val libraryHandle: PointerBuffer = stack.mallocPointer(1)
                    check(FreeType.FT_Init_FreeType(libraryHandle) == 0) { "FT_Init_FreeType failed" }
                    library = libraryHandle.get(0)
                    val faceHandle: PointerBuffer = stack.mallocPointer(1)
                    val error = FreeType.FT_New_Memory_Face(library, buffer, 0L, faceHandle)
                    check(error == 0) { "FT_New_Memory_Face failed with error $error" }
                    return TtfFace(library, FT_Face.create(faceHandle.get(0)), buffer)
                }
            } catch (throwable: Throwable) {
                if (library != MemoryUtil.NULL) FreeType.FT_Done_FreeType(library)
                MemoryUtil.memFree(buffer)
                throw throwable
            }
        }
    }
}

/** A glyph's advance in em together with its outline in font units. */
internal class TtfGlyphOutline(val advance: Float, val shape: MsdfShape)

private class OutlineDecomposer : AutoCloseable {
    private var shape = MsdfShape()
    private var contour: MsdfContour? = null
    private var unitsToPixels = 1f
    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f

    private val moveTo = FT_Outline_MoveToFunc.create { to, _ ->
        val point = FT_Vector.create(to)
        closeContour()
        contour = MsdfContour().also { shape.contours += it }
        startX = point.x().toFloat()
        startY = point.y().toFloat()
        currentX = startX
        currentY = startY
        0
    }

    private val lineTo = FT_Outline_LineToFunc.create { to, _ ->
        val point = FT_Vector.create(to)
        addLine(point.x().toFloat(), point.y().toFloat())
        0
    }

    private val conicTo = FT_Outline_ConicToFunc.create { control, to, _ ->
        val controlPoint = FT_Vector.create(control)
        val endPoint = FT_Vector.create(to)
        addQuadratic(
            controlPoint.x().toFloat(), controlPoint.y().toFloat(),
            endPoint.x().toFloat(), endPoint.y().toFloat(),
        )
        0
    }

    private val cubicTo = FT_Outline_CubicToFunc.create { first, second, to, _ ->
        val firstPoint = FT_Vector.create(first)
        val secondPoint = FT_Vector.create(second)
        val endPoint = FT_Vector.create(to)
        addCubic(
            firstPoint.x().toFloat(), firstPoint.y().toFloat(),
            secondPoint.x().toFloat(), secondPoint.y().toFloat(),
            endPoint.x().toFloat(), endPoint.y().toFloat(),
        )
        0
    }

    private val funcs: FT_Outline_Funcs = FT_Outline_Funcs.calloc()
        .move_to(moveTo)
        .line_to(lineTo)
        .conic_to(conicTo)
        .cubic_to(cubicTo)
        .shift(0)
        .delta(0)

    fun decompose(outline: org.lwjgl.util.freetype.FT_Outline, unitsToPixels: Float): MsdfShape {
        shape = MsdfShape()
        contour = null
        this.unitsToPixels = unitsToPixels
        val error = FreeType.FT_Outline_Decompose(outline, funcs, MemoryUtil.NULL)
        closeContour()
        if (error != 0) return MsdfShape()
        return shape.apply {
            contours.removeAll { it.edges.isEmpty() }
            orientForPositiveInside()
            colorEdges()
        }
    }

    override fun close() {
        funcs.free()
        moveTo.free()
        lineTo.free()
        conicTo.free()
        cubicTo.free()
    }

    /** FreeType leaves the closing segment implicit, so the loop is sealed when the contour ends. */
    private fun closeContour() {
        val current = contour ?: return
        if (current.edges.isNotEmpty() && (currentX != startX || currentY != startY)) {
            addLine(startX, startY)
        }
        contour = null
    }

    private fun addLine(x: Float, y: Float) {
        emit(floatArrayOf(currentX, currentY, x, y), null)
        currentX = x
        currentY = y
    }

    private fun addQuadratic(controlX: Float, controlY: Float, x: Float, y: Float) {
        val steps = flatteningSteps(
            distance(currentX, currentY, controlX, controlY) + distance(controlX, controlY, x, y)
        )
        val points = FloatArray((steps + 1) * 2)
        points[0] = currentX
        points[1] = currentY
        for (step in 1..steps) {
            val t = step.toFloat() / steps
            val inverse = 1f - t
            points[step * 2] = inverse * inverse * currentX + 2f * inverse * t * controlX + t * t * x
            points[step * 2 + 1] = inverse * inverse * currentY + 2f * inverse * t * controlY + t * t * y
        }
        emit(
            points,
            floatArrayOf(controlX - currentX, controlY - currentY, x - controlX, y - controlY),
        )
        currentX = x
        currentY = y
    }

    private fun addCubic(
        firstX: Float,
        firstY: Float,
        secondX: Float,
        secondY: Float,
        x: Float,
        y: Float,
    ) {
        val steps = flatteningSteps(
            distance(currentX, currentY, firstX, firstY) +
                    distance(firstX, firstY, secondX, secondY) +
                    distance(secondX, secondY, x, y)
        )
        val points = FloatArray((steps + 1) * 2)
        points[0] = currentX
        points[1] = currentY
        for (step in 1..steps) {
            val t = step.toFloat() / steps
            val inverse = 1f - t
            val a = inverse * inverse * inverse
            val b = 3f * inverse * inverse * t
            val c = 3f * inverse * t * t
            val d = t * t * t
            points[step * 2] = a * currentX + b * firstX + c * secondX + d * x
            points[step * 2 + 1] = a * currentY + b * firstY + c * secondY + d * y
        }
        val startX = if (firstX != currentX || firstY != currentY) firstX else secondX
        val startY = if (firstX != currentX || firstY != currentY) firstY else secondY
        val endX = if (secondX != x || secondY != y) secondX else firstX
        val endY = if (secondX != x || secondY != y) secondY else firstY
        emit(points, floatArrayOf(startX - currentX, startY - currentY, x - endX, y - endY))
        currentX = x
        currentY = y
    }

    private fun emit(points: FloatArray, tangents: FloatArray?) {
        if (points.size < 4) return
        contour?.edges?.add(MsdfEdge(points, tangents))
    }

    private fun flatteningSteps(controlLength: Float): Int {
        val pixels = controlLength * unitsToPixels
        return ceil(pixels / PixelsPerStep).toInt().coerceIn(MinSteps, MaxSteps)
    }

    private fun distance(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x1 - x0
        val dy = y1 - y0
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val PixelsPerStep = 0.75f
        const val MinSteps = 2
        const val MaxSteps = 32
    }
}
