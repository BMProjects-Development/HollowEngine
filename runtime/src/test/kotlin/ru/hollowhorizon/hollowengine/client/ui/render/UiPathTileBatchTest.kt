package ru.hollowhorizon.hollowengine.client.ui.render

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.shape.GenericShape
import ru.hollowhorizon.hollowengine.client.ui.shape.UiPathStrokeLineCap
import ru.hollowhorizon.hollowengine.client.ui.shape.UiPathStrokeLineJoin
import ru.hollowhorizon.hollowengine.client.ui.style.UiBackfaceVisibility
import ru.hollowhorizon.hollowengine.client.ui.style.UiFilterChain
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiPathTileBatchTest {
    @Test
    fun `closed path is encoded as unique segments and active tile quads`() {
        val batch = UiPathTileBatch()

        batch.append(command(square), UiMatrix4.identity())
        batch.prepareCpu()

        assertEquals(4, batch.segmentCount)
        assertTrue(batch.tileCount > 0)
        assertEquals(batch.tileCount * 6, batch.vertexCount)
        assertTrue(batch.fullCoverageTileCount > 0)
        assertTrue(batch.segmentIndexCount < batch.segmentCount * batch.tileCount)
    }

    @Test
    fun `fill implicitly closes an open contour`() {
        val openPath = GenericShape { size ->
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
        }
        val batch = UiPathTileBatch()

        batch.append(command(openPath), UiMatrix4.identity())
        batch.prepareCpu()

        assertTrue(!batch.isEmpty)
        assertEquals(3, batch.segmentCount)
    }

    @Test
    fun `round stroke is encoded as analytic tile coverage`() {
        val command = command(square).copy(
            fill = UiResolvedPaint.None,
            stroke = UiResolvedPaint.Color(UiColor.White),
            strokeWidth = 2f,
        )

        val batch = UiPathTileBatch()
        assertTrue(batch.canAppend(command))

        batch.append(command, UiMatrix4.identity())
        batch.prepareCpu()

        assertEquals(4, batch.segmentCount)
        assertTrue(batch.tileCount > 0)
        assertEquals(batch.tileCount, batch.strokeTileCount)
        assertEquals(0, batch.fullCoverageTileCount)
    }

    @Test
    fun `horizontal open line produces stroke tiles`() {
        val line = GenericShape { size ->
            moveTo(0f, size.height * 0.5f)
            lineTo(size.width, size.height * 0.5f)
        }
        val command = command(line).copy(
            fill = UiResolvedPaint.None,
            stroke = UiResolvedPaint.Color(UiColor.White),
            strokeWidth = 4f,
        )
        val batch = UiPathTileBatch()

        batch.append(command, UiMatrix4.identity())
        batch.prepareCpu()

        assertEquals(1, batch.segmentCount)
        assertTrue(batch.tileCount > 0)
        assertEquals(batch.tileCount, batch.strokeTileCount)
    }

    @Test
    fun `non-round stroke is converted to a cached outline for tile fill`() {
        val line = GenericShape { size ->
            moveTo(4f, size.height * 0.5f)
            lineTo(size.width - 4f, size.height * 0.5f)
        }
        val command = command(line).copy(
            fill = UiResolvedPaint.None,
            stroke = UiResolvedPaint.Color(UiColor.White),
            strokeWidth = 6f,
            strokeLineCap = UiPathStrokeLineCap.Square,
            strokeLineJoin = UiPathStrokeLineJoin.Bevel,
        )
        val batch = UiPathTileBatch()

        assertTrue(batch.canAppend(command))
        batch.append(command, UiMatrix4.identity())
        batch.prepareCpu()

        assertTrue(batch.segmentCount >= 4)
        assertTrue(batch.tileCount > 0)
        assertEquals(0, batch.strokeTileCount)
    }

    @Test
    fun `compute input packs path transform before tile candidates`() {
        val batch = UiPathTileBatch()
        batch.append(command(square), UiMatrix4.translation(2f, 3f, 0f))
        val data = FloatBuffer.allocate(batch.computeInputFloatCount)

        batch.writeComputeInputs(data)

        assertEquals(
            UiPathTileBatch.PathStride + batch.candidateCount * UiPathTileBatch.CandidateStride,
            data.position(),
        )
        assertEquals(2f, data[15])
        assertEquals(3f, data[19])
        assertEquals(0f, data[UiPathTileBatch.PathStride])
        assertEquals(0f, data[data.position() - 1])
    }

    @Test
    fun `path coverage effects are stored independently from paint`() {
        val batch = UiPathTileBatch()
        val effectCommand = command(square).copy(blurRadius = 2.5f, spreadRadius = -1.25f)

        batch.append(effectCommand, UiMatrix4.identity())
        batch.prepareCpu()
        val tiles = IntBuffer.allocate(batch.tileIntCount)
        batch.writeTiles(tiles)

        assertTrue(batch.tileCount > 0)
        assertEquals(-1.25f, Float.fromBits(tiles[6]))
        assertEquals(2.5f, Float.fromBits(tiles[7]))
    }

    @Test
    fun `stable path reuses flattened geometry and prepared cpu tiles`() {
        var geometryBuilds = 0
        val shape = GenericShape { size ->
            geometryBuilds++
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        val batch = UiPathTileBatch()

        repeat(2) {
            batch.append(command(shape), UiMatrix4.identity())
            batch.prepareCpu()
            assertTrue(batch.tileCount > 0)
            batch.clear()
        }

        assertEquals(1, geometryBuilds)
        assertEquals(1, batch.cpuRasterizationCount)
    }

    @Test
    fun `cached tiles are rebased into a shared batch`() {
        val batch = UiPathTileBatch()
        val redCommand = command(square).copy(fill = UiResolvedPaint.Color(UiColor(1f, 0f, 0f, 1f)))

        repeat(2) {
            batch.append(command(square), UiMatrix4.identity())
            batch.append(redCommand, UiMatrix4.translation(80f, 0f, 0f))
            batch.prepareCpu()
            assertValidTileReferences(batch)
            batch.clear()
        }

        assertEquals(1, batch.cpuRasterizationCount)
    }

    private fun assertValidTileReferences(batch: UiPathTileBatch) {
        val indices = IntBuffer.allocate(batch.segmentIndexCount)
        val tiles = IntBuffer.allocate(batch.tileIntCount)
        batch.writeSegmentIndices(indices)
        batch.writeTiles(tiles)

        for (index in 0 until indices.position()) {
            assertTrue(indices[index] in 0 until batch.segmentCount)
        }
        val paintIndices = HashSet<Int>()
        var offset = 0
        while (offset < tiles.position()) {
            val segmentStart = tiles[offset]
            val segmentCount = tiles[offset + 1]
            assertTrue(segmentStart >= 0)
            assertTrue(segmentStart + segmentCount <= batch.segmentIndexCount)
            paintIndices += tiles[offset + 2]
            offset += UiPathTileBatch.TileStride
        }
        assertEquals(setOf(0, 1), paintIndices)

        val vertices = FloatBuffer.allocate(batch.vertexFloatCount)
        batch.writeVertices(vertices)
        var maxWorldX = Float.NEGATIVE_INFINITY
        var maxLocalX = Float.NEGATIVE_INFINITY
        offset = 0
        while (offset < vertices.position()) {
            maxWorldX = maxOf(maxWorldX, vertices[offset])
            maxLocalX = maxOf(maxLocalX, vertices[offset + 3])
            offset += UiPathTileBatch.VertexStride
        }
        assertTrue(maxWorldX > 80f)
        assertEquals(80f, maxWorldX - maxLocalX, 0.001f)
    }

    @Test
    fun `stable raster switches from compute preference to baked cpu after observation`() {
        val batch = UiPathTileBatch()

        batch.append(command(square), UiMatrix4.identity())
        assertTrue(!batch.prefersBakedCpu)
        batch.clear()
        batch.append(command(square), UiMatrix4.translation(20f, 10f, 0f))

        assertTrue(batch.prefersBakedCpu)
    }

    private fun command(shape: GenericShape): DrawShapeCommand = DrawShapeCommand(
        node = BoxNode(),
        rect = UiRect(0f, 0f, 64f, 64f),
        shape = shape,
        fill = UiResolvedPaint.Color(UiColor.White),
        stroke = UiResolvedPaint.None,
        strokeWidth = 0f,
        opacity = 1f,
        transform = UiMatrix4.identity(),
        filter = UiFilterChain.Empty,
        backfaceVisibility = UiBackfaceVisibility.VISIBLE,
    )

    private companion object {
        val square = GenericShape { size ->
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
    }
}
