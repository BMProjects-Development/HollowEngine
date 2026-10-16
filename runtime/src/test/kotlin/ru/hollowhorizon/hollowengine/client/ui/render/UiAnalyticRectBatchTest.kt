package ru.hollowhorizon.hollowengine.client.ui.render

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.BoxNode
import ru.hollowhorizon.hollowengine.client.ui.DrawBoxCommand
import ru.hollowhorizon.hollowengine.client.ui.DrawShadowCommand
import ru.hollowhorizon.hollowengine.client.ui.UiBorder
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.UiInsets
import ru.hollowhorizon.hollowengine.client.ui.UiMatrix4
import ru.hollowhorizon.hollowengine.client.ui.UiResolvedPaint
import ru.hollowhorizon.hollowengine.client.ui.UiVec3
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.px
import ru.hollowhorizon.hollowengine.client.ui.style.UiBackfaceVisibility
import ru.hollowhorizon.hollowengine.client.ui.style.UiFilterChain
import ru.hollowhorizon.hollowengine.client.ui.style.UiImageFit
import ru.hollowhorizon.hollowengine.client.ui.style.UiShadow
import ru.hollowhorizon.hollowengine.client.ui.text.UiGlyphAtlasPage
import ru.hollowhorizon.hollowengine.client.ui.text.UiGlyphSampling
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiAnalyticRectBatchTest {
    @Test
    fun `rounded fill and uniform border use one analytic quad`() {
        val batch = UiAnalyticRectBatch()
        val command = command(
            border = UiBorder(width = UiInsets.all(2.px), color = UiColor.White, radius = 12f),
        )

        assertTrue(batch.canAppend(command))
        batch.append(command, UiMatrix4.identity(), UiShaderClip.None)

        assertEquals(1, batch.instanceCount)
        assertEquals(UiAnalyticRectBatch.RecordStride, batch.recordFloatCount)
    }

    @Test
    fun `each record carries its clip rectangle`() {
        val batch = UiAnalyticRectBatch()
        val command = command(
            border = UiBorder(width = UiInsets.all(2.px), color = UiColor.White, radius = 12f),
        )
        val clip = UiShaderClip(4f, 8f, 40f, 30f)
        batch.append(command, UiMatrix4.identity(), clip)

        // The clip rect is the last texel (floats 12..15) of the 16-float record.
        val records = FloatArray(batch.recordFloatCount)
        val buffer = java.nio.FloatBuffer.wrap(records)
        batch.writeRecords(buffer)
        assertEquals(clip.minX, records[12])
        assertEquals(clip.minY, records[13])
        assertEquals(clip.maxX, records[14])
        assertEquals(clip.maxY, records[15])
    }

    @Test
    fun `an instance encodes the row-major transform then the local quad bounds`() {
        val batch = UiAnalyticRectBatch()
        batch.append(
            command(border = UiBorder(width = UiInsets.all(2.px), color = UiColor.White, radius = 12f)),
            UiMatrix4.identity(),
            UiShaderClip.None,
        )

        val instance = FloatArray(batch.instanceFloatCount)
        batch.writeInstances(java.nio.FloatBuffer.wrap(instance))
        assertEquals(UiAnalyticRectBatch.InstanceStride, instance.size)
        // Rows 0-3 of the identity matrix.
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        identity.forEachIndexed { index, value -> assertEquals(value, instance[index], "row float $index") }
        // Local bounds (0,0)-(w,h).
        assertEquals(0f, instance[16]); assertEquals(0f, instance[17])
        assertEquals(80f, instance[18]); assertEquals(48f, instance[19])
    }

    @Test
    fun `a glyph encodes marker, uv, color and clip into its record`() {
        val batch = UiAnalyticRectBatch()
        val clip = UiShaderClip(1f, 2f, 3f, 4f)
        batch.appendGlyph(
            UiMatrix4.identity(),
            10f, 20f, 30f, 40f,          // local bounds
            0.1f, 0.2f, 0.3f, 0.4f,       // uv rect
            UiColor(0.5f, 0.6f, 0.7f, 0.8f),
            clip,
            page(texture = 42),
        )

        assertEquals(1, batch.instanceCount)
        assertEquals(1, batch.glyphPageCount)
        assertEquals(42, batch.glyphPageTexture(0))

        val records = FloatArray(batch.recordFloatCount)
        batch.writeRecords(java.nio.FloatBuffer.wrap(records))
        assertEquals(UiAnalyticRectBatch.GlyphMarker, records[0], "glyph marker in texel 0")
        assertEquals(0f, records[3], "page index in texel 0")
        assertEquals(0.1f, records[4]); assertEquals(0.4f, records[7]) // uv rect (texel 1)
        assertEquals(0.5f, records[8]); assertEquals(0.8f, records[11]) // color (texel 2)
        assertEquals(1f, records[12]); assertEquals(4f, records[15]) // clip (texel 3)
    }

    @Test
    fun `glyphs from several sheets share one batch`() {
        val batch = UiAnalyticRectBatch()
        val latin = page(texture = 1)
        val cyrillic = page(texture = 2)
        appendGlyph(batch, latin)
        appendGlyph(batch, cyrillic)
        appendGlyph(batch, latin)

        assertEquals(3, batch.instanceCount)
        assertEquals(2, batch.glyphPageCount, "the two sheets take one slot each, not one per run")

        val records = FloatArray(batch.recordFloatCount)
        batch.writeRecords(java.nio.FloatBuffer.wrap(records))
        val stride = UiAnalyticRectBatch.RecordStride
        assertEquals(0f, records[3], "first glyph names page 0")
        assertEquals(1f, records[stride + 3], "second glyph names page 1")
        assertEquals(0f, records[2 * stride + 3], "third glyph is back on page 0, not a new slot")
    }

    @Test
    fun `a page beyond the batch's capacity is refused so the caller can flush`() {
        val batch = UiAnalyticRectBatch()
        repeat(UiAnalyticRectBatch.MaxGlyphPages) { index -> appendGlyph(batch, page(texture = index + 1)) }

        assertEquals(UiAnalyticRectBatch.MaxGlyphPages, batch.glyphPageCount)
        assertTrue(batch.acceptsGlyphPage(page(texture = 1)), "a page already here always fits")
        assertFalse(batch.acceptsGlyphPage(page(texture = 99)), "a new page does not")
    }

    @Test
    fun `clearing releases the atlas slots`() {
        val batch = UiAnalyticRectBatch()
        appendGlyph(batch, page(texture = 1))
        batch.clear()

        assertEquals(0, batch.glyphPageCount)
        assertTrue(batch.acceptsGlyphPage(page(texture = 99)))
    }

    @Test
    fun `per-page sizes are laid out for the shader's uniform arrays`() {
        val batch = UiAnalyticRectBatch()
        appendGlyph(batch, page(texture = 1, distanceRange = 2f, width = 512f, height = 8192f))
        appendGlyph(batch, page(texture = 2, distanceRange = 8f, width = 1024f, height = 1024f))

        assertEquals(2f, batch.glyphPageDistanceRanges()[0])
        assertEquals(8f, batch.glyphPageDistanceRanges()[1])
        assertEquals(512f, batch.glyphPageSizes()[0])
        assertEquals(8192f, batch.glyphPageSizes()[1])
        assertEquals(1024f, batch.glyphPageSizes()[2])
        assertEquals(1024f, batch.glyphPageSizes()[3])
    }

    @Test
    fun `a span background joins the glyph batch instead of interrupting it`() {
        val batch = UiAnalyticRectBatch()
        appendGlyph(batch, page(texture = 1))
        batch.appendSolidRect(UiMatrix4.identity(), 40f, 12f, 2f, UiColor.White, UiShaderClip.None)
        appendGlyph(batch, page(texture = 1))

        assertEquals(3, batch.instanceCount, "background and glyphs go out together")
        assertEquals(1, batch.glyphPageCount, "a plain rect claims no atlas slot")
    }

    private fun appendGlyph(batch: UiAnalyticRectBatch, page: UiGlyphAtlasPage) = batch.appendGlyph(
        UiMatrix4.identity(),
        0f, 0f, 8f, 8f,
        0f, 0f, 1f, 1f,
        UiColor.White,
        UiShaderClip.None,
        page,
    )

    private fun page(
        texture: Int,
        distanceRange: Float = 2f,
        width: Float = 512f,
        height: Float = 512f,
    ) = UiGlyphAtlasPage(texture, width, height, distanceRange, UiGlyphSampling.MSDF)

    @Test
    fun `nonuniform border remains on geometry fallback`() {
        val command = command(
            border = UiBorder(
                width = UiInsets(1.px, 2.px, 1.px, 2.px),
                color = UiColor.White,
                radius = 8f,
            ),
        )

        assertFalse(UiAnalyticRectBatch().canAppend(command))
    }

    @Test
    fun `image paint remains on textured renderer`() {
        assertFalse(UiAnalyticRectBatch().canAppend(command(paint = UiResolvedPaint.Image("test:image"))))
    }

    @Test
    fun `plain fill joins the analytic batch and is hard-filled by the shader`() {
        // A flat color rect (no radius, no border) batches here; the shader hard-fills it so abutting
        // opaque fills stay seam-free while still sharing one instanced draw with rounded/bordered rects.
        val batch = UiAnalyticRectBatch()
        val command = command()

        assertTrue(batch.canAppend(command))
        batch.append(command, UiMatrix4.identity(), UiShaderClip.None)

        assertEquals(1, batch.instanceCount)
        val records = FloatArray(batch.recordFloatCount)
        batch.writeRecords(java.nio.FloatBuffer.wrap(records))
        assertEquals(0f, records[2], "radius")
        assertEquals(0f, records[3], "border width")
    }

    @Test
    fun `image fill stays off the SDF pipeline`() {
        assertFalse(UiAnalyticRectBatch().canAppend(command(paint = UiResolvedPaint.Image("test:image"))))
    }

    @Test
    fun `rounded shadow uses one expanded analytic quad`() {
        val node = BoxNode()
        val command = DrawShadowCommand(
            node = node,
            rect = UiRect(0f, 0f, 80f, 48f),
            radius = 12f,
            shape = null,
            shadows = emptyList(),
            opacity = 0.8f,
            transform = UiMatrix4.identity(),
            filter = UiFilterChain.Empty,
            backfaceVisibility = UiBackfaceVisibility.VISIBLE,
        )

        val batch = UiAnalyticRectBatch()
        batch.appendShadow(
            command, UiShadow(blur = 6f, spread = 2f, color = UiColor.Black), command.transform, UiShaderClip.None,
        )

        assertEquals(1, batch.instanceCount)
        assertEquals(UiAnalyticRectBatch.RecordStride, batch.recordFloatCount)
    }

    @Test
    fun `inset shadow stays on the box and moves its hole, not its quad`() {
        val command = DrawShadowCommand(
            node = BoxNode(),
            rect = UiRect(0f, 0f, 80f, 48f),
            radius = 4f,
            shape = null,
            shadows = emptyList(),
            opacity = 1f,
            transform = UiMatrix4.identity(),
            filter = UiFilterChain.Empty,
            backfaceVisibility = UiBackfaceVisibility.VISIBLE,
            borderWidth = 1f,
        )

        val batch = UiAnalyticRectBatch()
        val shadow = UiShadow(offset = UiVec3(0f, 1f, 0f), blur = 3f, spread = 2f, color = UiColor.White, inset = true)
        batch.appendShadow(command, shadow, command.transform, UiShaderClip.None)

        val instance = FloatArray(batch.instanceFloatCount)
        batch.writeInstances(java.nio.FloatBuffer.wrap(instance))
        assertEquals(0f, instance[16]); assertEquals(0f, instance[17])
        assertEquals(80f, instance[18]); assertEquals(48f, instance[19])

        val records = FloatArray(batch.recordFloatCount)
        batch.writeRecords(java.nio.FloatBuffer.wrap(records))
        assertEquals(1f, records[3], "border width the shadow starts inside")
        assertTrue(records[7] > 1.5f, "inset mode, apart from the outer shadow's")
        assertEquals(0f, records[8]); assertEquals(1f, records[9])
    }

    private fun command(
        paint: UiResolvedPaint = UiResolvedPaint.Color(UiColor(0.2f, 0.4f, 0.8f, 1f)),
        border: UiBorder = UiBorder(),
    ) = DrawBoxCommand(
        node = BoxNode(),
        rect = UiRect(0f, 0f, 80f, 48f),
        paint = paint,
        border = border,
        shadows = emptyList(),
        opacity = 1f,
        tint = UiColor.White,
        transform = UiMatrix4.identity(),
        renderToFramebuffer = false,
        fit = UiImageFit.STRETCH,
        slice = UiInsets.Zero,
        filter = UiFilterChain.Empty,
        backfaceVisibility = UiBackfaceVisibility.VISIBLE,
    )
}
