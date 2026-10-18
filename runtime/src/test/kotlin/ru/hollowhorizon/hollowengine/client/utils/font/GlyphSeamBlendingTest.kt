package ru.hollowhorizon.hollowengine.client.utils.font

import ru.hollowhorizon.hollowengine.client.ui.text.MinGlyphPxRange
import ru.hollowhorizon.hollowengine.client.ui.text.glyphCoverage
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the seams between neighboring letters.
 */
class GlyphSeamBlendingTest {
    private val maximumSeamDeficit = 0.03f

    private val bakedPixelSize = 48f
    private val bakedPixelRange = 2f

    @Test
    fun `neighbouring letters composite without a visible seam`() {
        val face = openTestFace() ?: return
        val codepoints = ((0x20..0x7E) + (0x400..0x45F)).toIntArray()
        val atlas: BakedMsdfAtlas
        val advances = HashMap<Int, Float>()
        face.use { face ->
            atlas = bakeMsdfAtlas(face, MsdfBakeSpec(bakedPixelSize, bakedPixelRange, codepoints))
            for (cp in codepoints) {
                face.loadGlyph(cp, bakedPixelSize)?.let { advances[cp] = it.advance }
            }
        }
        val byChar = atlas.meta.glyphs.associateBy { it.unicode }

        var worst = 0f
        var worstAt = ""
        for (fontSize in listOf(12f, 16f, 20f, 24f, 32f)) {
            for (subpixel in listOf(0f, 0.25f, 0.5f, 0.75f)) {
                for (text in listOf("wrap", "французских", "quick", "brown", "переносить")) {
                    val width = 512
                    val height = 64
                    val blended = FloatArray(width * height)
                    val union = FloatArray(width * height)
                    var penX = 10f + subpixel
                    for (ch in text) {
                        byChar[ch.code]?.takeIf { !it.isEmpty() }?.let { glyph ->
                            drawGlyph(atlas, glyph, penX, 44f, fontSize, width, height, blended, union)
                        }
                        penX += (advances[ch.code] ?: 0f) * fontSize
                    }
                    for (index in blended.indices) {
                        val deficit = min(union[index], 1f) - blended[index]
                        if (deficit > worst) {
                            worst = deficit
                            worstAt = "'$text' at ${fontSize}px, subpixel $subpixel, " +
                                    "pixel (${index % width}, ${index / width})"
                        }
                    }
                }
            }
        }
        assertTrue(
            worst <= maximumSeamDeficit,
            "seam deficit grew to $worst - $worstAt. Something widened the glyph edge; check the " +
                    "baked distance range first.",
        )
    }

    private fun drawGlyph(
        atlas: BakedMsdfAtlas,
        glyph: MsdfGlyph,
        penX: Float,
        baseline: Float,
        fontSize: Float,
        width: Int,
        height: Int,
        blended: FloatArray,
        union: FloatArray,
    ) {
        val pb = glyph.planeBounds
        val ab = glyph.atlasBounds
        val x0 = penX + pb.left * fontSize
        val x1 = penX + pb.right * fontSize
        val yTop = baseline - pb.top * fontSize
        val yBottom = baseline - pb.bottom * fontSize
        val pxRange = max(atlas.meta.atlas.distanceRange / ((ab.right - ab.left) / (x1 - x0)), MinGlyphPxRange)
        for (py in floor(yTop).toInt()..floor(yBottom).toInt()) {
            for (px in floor(x0).toInt()..floor(x1).toInt()) {
                if (px < 0 || py < 0 || px >= width || py >= height) continue
                val cx = px + 0.5f
                val cy = py + 0.5f
                if (cx !in x0..x1 || cy < yTop || cy > yBottom) continue
                val fx = (cx - x0) / (x1 - x0)
                val fy = (cy - yTop) / (yBottom - yTop)
                val median = atlas.sampleMedian(
                    ab.left + (ab.right - ab.left) * fx,
                    ab.top - (ab.top - ab.bottom) * fy,
                )
                val coverage = glyphCoverage(median - 0.5f, bias = 0f, pxRange = pxRange)
                if (coverage <= 0f) continue
                val index = py * width + px
                blended[index] = coverage + blended[index] * (1f - coverage)
                union[index] += coverage
            }
        }
    }
}
