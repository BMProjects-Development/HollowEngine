package ru.hollowhorizon.hollowengine.client.ui.render

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.BoxNode
import ru.hollowhorizon.hollowengine.client.ui.DrawShapeCommand
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.UiCommandRenderer
import ru.hollowhorizon.hollowengine.client.ui.UiMeasurePolicies
import ru.hollowhorizon.hollowengine.client.ui.UiResolvedPaint
import ru.hollowhorizon.hollowengine.client.ui.drawBehind
import ru.hollowhorizon.hollowengine.client.ui.layout.UiLayoutPipeline
import ru.hollowhorizon.hollowengine.client.ui.px
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollState
import ru.hollowhorizon.hollowengine.client.ui.shape.SvgFileParser
import ru.hollowhorizon.hollowengine.client.ui.shape.SvgPathShape
import ru.hollowhorizon.hollowengine.client.ui.shape.UiPathCommand
import ru.hollowhorizon.hollowengine.client.ui.shape.UiShapeSize
import ru.hollowhorizon.hollowengine.client.ui.shape.UiSvgFilterEffect
import ru.hollowhorizon.hollowengine.client.ui.shape.UiSvgPathDocument
import ru.hollowhorizon.hollowengine.client.ui.shape.toAwtPath
import java.awt.geom.Area
import ru.hollowhorizon.hollowengine.client.ui.size
import ru.hollowhorizon.hollowengine.client.ui.style.UiModifierResolver
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UiSvgDocumentRenderTest {
    @Test
    fun `stroke-only svg keeps independent elements and native paint`() {
        val document = parseResource("/assets/hollowengine/textures/gui/icons/light_spot.svg")
        val commands = renderDocument(document, 120f, 120f)

        assertEquals(6, document.elements.size)
        assertEquals(document.elements.size, commands.size)
        commands.forEach { command ->
            assertEquals(UiResolvedPaint.None, command.stroke)
            assertEquals(UiColor.White, assertIs<UiResolvedPaint.Color>(command.fill).color)
        }
    }

    @Test
    fun `closed stroke outline does not cover its hollow center`() {
        val document = parseResource("/assets/hollowengine/textures/gui/icons/light_spot.svg")
        val shape = SvgPathShape(document.elements.first().path, document.viewBox)
        val command = renderDocument(document, 120f, 120f).first()
        val batch = UiPathTileBatch()

        batch.append(command, command.transform)
        batch.prepareCpu()

        val path = shape.createPath(UiShapeSize(120f, 120f))
        assertTrue(!Area(path.toAwtPath()).contains(62.5, 42.5), "AWT stroke outline unexpectedly covers center")
        assertTrue(!batch.coversLocalPoint(62.5f, 42.5f), "Tile raster unexpectedly covers center")
    }

    @Test
    fun `pipeline keeps element colors and gaussian blur without synthetic geometry`() {
        val document = parseResource("/assets/hollowengine/ui/shapes/demo-pipeline.svg")
        val commands = renderDocument(document, 398f, 126f)

        assertEquals(document.elements.size, commands.size)
        assertEquals(1, commands.count { it.blurRadius > 0f })
        assertTrue(commands.all { it.fill is UiResolvedPaint.Color })
        assertTrue(commands.map { (it.fill as UiResolvedPaint.Color).color }.distinct().size >= 4)

        val nativeBatch = UiPathTileBatch()
        commands.forEach { nativeBatch.append(it, it.transform) }
        val legacyBatch = UiPathTileBatch()
        val combined = commands.first().copy(
            shape = SvgPathShape(document.path, document.viewBox),
            fill = UiResolvedPaint.Color(UiColor.White),
            stroke = UiResolvedPaint.Color(UiColor.White),
            strokeWidth = 1f,
            blurRadius = 0f,
        )
        legacyBatch.append(combined, combined.transform)

        assertTrue(nativeBatch.computeSegmentIndexCapacity < legacyBatch.computeSegmentIndexCapacity)
        assertTrue(document.elements[1].path.commands.count { it is UiPathCommand.CubicTo } >= 4)
    }

    @Test
    fun `quest map outline does not overpaint its contents`() {
        val document = parseResource("/assets/hollowengine/ui/shapes/demo-quest-map.svg")

        assertTrue(document.elements.size > 8)
        assertEquals(UiPaint.Color(UiColor(214f / 255f, 230f / 255f, 1f)), document.elements.last().paint)
    }

    @Test
    fun `a design tool's gradient fill spans the box it is drawn into`() {
        val document = parseResource("/assets/hollowengine/textures/gui/icons/success.svg")
        val gradient = assertIs<UiPaint.LinearGradient>(document.elements.first().paint)

        // The icon's own axis runs down and to the left, so the angle has to as well.
        assertTrue(gradient.angleDegrees > 180f && gradient.angleDegrees < 270f, "angle ${gradient.angleDegrees}")
        assertEquals(0f, gradient.stops.first().offset)
        assertEquals(1f, gradient.stops.last().offset)
        assertTrue(gradient.stops.zipWithNext().all { (a, b) -> a.offset <= b.offset }, "stops out of order")
        assertTrue(gradient.stops.any { it.color.green > it.color.red }, "the green of the authored gradient is gone")
    }

    @Test
    fun `a mask's backing sheet is never painted over the icon`() {
        val document = parseResource("/assets/hollowengine/textures/gui/icons/changed.svg")

        // The icon's masks each carry a white rectangle covering everything, which is theirs to cut
        assertTrue(
            document.elements.none { element ->
                element.paint == UiPaint.Color(UiColor.White) &&
                        (element.path.bounds()?.width ?: 0f) >= document.viewBox.width
            },
            "a mask's own sheet ended up among the drawn elements",
        )
    }

    @Test
    fun `a shadow spelled out as filter primitives does not blur the shape it belongs to`() {
        val document = parseResource("/assets/hollowengine/textures/gui/icons/changed.svg")
        val effects = document.elements.flatMap { it.filterEffects }

        val shadow = assertIs<UiSvgFilterEffect.DropShadow>(effects.first { it is UiSvgFilterEffect.DropShadow })
        assertEquals(1.6f, shadow.offsetX)
        assertEquals(1.6f, shadow.offsetY)
        assertTrue(shadow.color.red > 0.9f && shadow.color.blue < 0.3f, "shadow lost its color: ${shadow.color}")

        assertEquals(1, effects.count { it is UiSvgFilterEffect.GaussianBlur })
    }

    @Test
    fun `complex svg demos stay within bounded tile budgets`() {
        val resources = listOf(
            "/assets/hollowengine/textures/gui/icons/light_spot.svg",
            "/assets/hollowengine/ui/shapes/demo-pipeline.svg",
            "/assets/hollowengine/ui/shapes/demo-quest-map.svg",
            "/assets/hollowengine/ui/shapes/demo-viewport-stack.svg",
        )

        for (resource in resources) {
            val document = parseResource(resource)
            val commands = renderDocument(document, document.viewBox.width, document.viewBox.height)
            val batch = UiPathTileBatch()
            commands.forEach { batch.append(it, it.transform) }

            assertTrue(batch.segmentCount < 4_096, "$resource produced ${batch.segmentCount} segments")
            assertTrue(batch.candidateCount < 16_384, "$resource produced ${batch.candidateCount} candidates")
            assertTrue(
                batch.computeSegmentIndexCapacity < 1_000_000,
                "$resource reserved ${batch.computeSegmentIndexCapacity} segment indices",
            )
        }
    }

    private fun renderDocument(
        document: UiSvgPathDocument,
        width: Float,
        height: Float,
    ): List<DrawShapeCommand> {
        val node = BoxNode(
            modifiers = listOf(
                Modifier.size(width.px, height.px).drawBehind { drawSvg(document) },
            ),
        )
        val root = BoxNode(measurePolicy = UiMeasurePolicies.Column).also { it.children += node }
        UiModifierResolver().resolve(root, animate = false)
        val layout = UiLayoutPipeline().compute(root, width, height, UiScrollState())
        return UiCommandRenderer().collect(root, layout).filterIsInstance<DrawShapeCommand>()
    }

    private fun parseResource(path: String) = SvgFileParser.parse(
        checkNotNull(javaClass.getResourceAsStream(path)) { "Missing test resource $path" }
            .bufferedReader()
            .use { it.readText() },
    )
}

private fun UiPathTileBatch.coversLocalPoint(x: Float, y: Float): Boolean {
    val vertices = java.nio.FloatBuffer.allocate(vertexFloatCount)
    val tiles = java.nio.IntBuffer.allocate(tileIntCount)
    val indices = java.nio.IntBuffer.allocate(segmentIndexCount)
    val segments = java.nio.FloatBuffer.allocate(segmentFloatCount)
    writeVertices(vertices)
    writeTiles(tiles)
    writeSegmentIndices(indices)
    writeSegments(segments)

    var vertexOffset = 0
    while (vertexOffset < vertices.position()) {
        val tileIndex = vertices[vertexOffset + 5].toInt()
        val maxX = vertices[vertexOffset + UiPathTileBatch.VertexStride * 2 + 3]
        val maxY = vertices[vertexOffset + UiPathTileBatch.VertexStride + 4]
        val minX = vertices[vertexOffset + 3]
        val minY = vertices[vertexOffset + 4]
        if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
            val tileOffset = tileIndex * UiPathTileBatch.TileStride
            if (tiles[tileOffset + 3] and UiPathTileBatch.TileFullCoverage != 0) return true
            var winding = 0
            val segmentStart = tiles[tileOffset]
            val segmentCount = tiles[tileOffset + 1]
            for (index in 0 until segmentCount) {
                val segmentOffset = indices[segmentStart + index] * UiPathTileBatch.SegmentStride
                val x0 = segments[segmentOffset]
                val y0 = segments[segmentOffset + 1]
                val x1 = segments[segmentOffset + 2]
                val y1 = segments[segmentOffset + 3]
                if (!((y0 <= y && y1 > y) || (y1 <= y && y0 > y))) continue
                val crossingX = x0 + (x1 - x0) * (y - y0) / (y1 - y0)
                if (crossingX <= x) winding += if (y1 > y0) 1 else -1
            }
            return winding != 0
        }
        vertexOffset += UiPathTileBatch.VertexStride * 6
    }
    return false
}
