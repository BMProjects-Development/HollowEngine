package ru.hollowhorizon.hollowengine.client.ui.render

import ru.hollowhorizon.hollowengine.client.ui.DrawShapeCommand
import ru.hollowhorizon.hollowengine.client.ui.UiMatrix4
import ru.hollowhorizon.hollowengine.client.ui.UiResolvedPaint
import ru.hollowhorizon.hollowengine.client.ui.shape.UiPathStrokeLineCap
import ru.hollowhorizon.hollowengine.client.ui.shape.UiPathStrokeLineJoin
import ru.hollowhorizon.hollowengine.client.ui.shape.UiShapeSize
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.*
import kotlin.math.*

/**
 * Path input plus lazily produced CPU fallback tiles. The raw segment/path/candidate buffers are
 * consumed directly by the compute coarse pass; CPU binning is only performed when compute is not
 * available or its bounded worst-case index budget would be excessive.
 */
internal class UiPathTileBatch {
    private val segments = UiFloatArrayBuilder()
    private val paths = UiFloatArrayBuilder()
    private val candidates = UiIntArrayBuilder()
    private val paints = UiFloatArrayBuilder()
    private val stops = UiFloatArrayBuilder()
    private val paintEncoder = UiPaintBufferEncoder(paints, stops)
    private val vertices = UiFloatArrayBuilder()
    private val segmentIndices = UiIntArrayBuilder()
    private val tiles = UiIntArrayBuilder()
    private val transforms = ArrayList<UiMatrix4>()
    private val cache = UiPathTileCache()
    private val rasterKeys = ArrayList<UiPathRasterKey>()
    private var rowIndexScratch = IntArray(64)
    private var crossingXScratch = FloatArray(64)
    private var crossingDeltaScratch = IntArray(64)
    private var boundaryColumnScratch = BooleanArray(16)
    private val scaleScratch = FloatArray(2)
    private var worstCaseSegmentIndexCount = 0L
    private var cpuPrepared = false
    private var bakedCpuPreferred = true

    val isEmpty: Boolean get() = candidates.size == 0
    val pathCount: Int get() = paths.size / PathStride
    val candidateCount: Int get() = candidates.size / CandidateStride
    val segmentCount: Int get() = segments.size / SegmentStride
    val segmentFloatCount: Int get() = segments.size
    val computeInputFloatCount: Int get() = paths.size + candidates.size
    val paintFloatCount: Int get() = paints.size
    val stopFloatCount: Int get() = stops.size
    val vertexCount: Int get() = vertices.size / VertexStride
    val vertexFloatCount: Int get() = vertices.size
    val segmentIndexCount: Int get() = segmentIndices.size
    val tileCount: Int get() = tiles.size / TileStride
    val tileIntCount: Int get() = tiles.size
    var cpuRasterizationCount: Int = 0
        private set
    val computeSegmentIndexCapacity: Int get() = worstCaseSegmentIndexCount.toInt()
    val canDispatchCompute: Boolean
        get() = candidateCount <= MaxComputeCandidates &&
                worstCaseSegmentIndexCount <= MaxComputeSegmentIndices
    val prefersBakedCpu: Boolean get() = bakedCpuPreferred
    val fullCoverageTileCount: Int
        get() {
            var count = 0
            var offset = 3
            while (offset < tiles.size) {
                if (tiles[offset] and TileFullCoverage != 0) count++
                offset += TileStride
            }
            return count
        }
    val strokeTileCount: Int
        get() {
            var count = 0
            var offset = 4
            while (offset < tiles.size) {
                if (tiles[offset] == TileStyleStroke) count++
                offset += TileStride
            }
            return count
        }

    fun canAppend(command: DrawShapeCommand): Boolean {
        val hasFill = command.fill.isBufferPaint()
        val hasStroke = command.stroke.isBufferPaint() && command.strokeWidth > 0f
        if (!hasFill && !hasStroke) return false
        if (command.fill != UiResolvedPaint.None && !hasFill) return false
        if (command.stroke != UiResolvedPaint.None && command.strokeWidth > 0f && !hasStroke) return false
        return true
    }

    fun append(command: DrawShapeCommand, transform: UiMatrix4) {
        val width = command.rect.width
        val height = command.rect.height
        if (width <= 0f || height <= 0f || command.opacity <= 0f) return
        invalidateCpuOutput()

        val size = UiShapeSize(width, height)
        transform.axisScales(scaleScratch)
        val deviceScale = max(scaleScratch[0], scaleScratch[1]).coerceAtLeast(MinimumScale)
        val tileSize = (DeviceTileSize / deviceScale).coerceIn(MinimumTileSize, MaximumTileSize)
        val aaMargin = 1f / deviceScale
        if (command.fill.isBufferPaint()) {
            appendPath(
                command,
                transform,
                command.fill,
                cache.geometry(command.shape, size, UiPathGeometryMode.Fill),
                width,
                height,
                tileSize,
                aaMargin,
                0f,
                command.blurRadius,
                command.spreadRadius,
            )
        }
        if (command.stroke.isBufferPaint() && command.strokeWidth > 0f) {
            val analyticStroke = command.strokeLineCap == UiPathStrokeLineCap.Round &&
                    command.strokeLineJoin == UiPathStrokeLineJoin.Round
            val geometryMode = if (analyticStroke) {
                UiPathGeometryMode.Stroke
            } else {
                UiPathGeometryMode.StrokeOutline(
                    command.strokeWidth.toBits(),
                    command.strokeLineCap,
                    command.strokeLineJoin,
                )
            }
            appendPath(
                command,
                transform,
                command.stroke,
                cache.geometry(command.shape, size, geometryMode),
                width,
                height,
                tileSize,
                aaMargin,
                if (analyticStroke) command.strokeWidth * 0.5f else 0f,
                command.blurRadius,
                command.spreadRadius,
            )
        }
    }

    private fun appendPath(
        command: DrawShapeCommand,
        transform: UiMatrix4,
        paint: UiResolvedPaint,
        geometry: UiPathGeometryTemplate,
        width: Float,
        height: Float,
        tileSize: Float,
        aaMargin: Float,
        strokeRadius: Float,
        blurRadius: Float,
        spreadRadius: Float,
    ) {
        if (geometry.segments.isEmpty()) return
        val rasterMargin = aaMargin + strokeRadius + abs(spreadRadius) + blurRadius * BlurExtentFactor
        val bounds = geometry.bounds?.expanded(rasterMargin) ?: return
        val minTileX = floor(bounds.minX / tileSize).toInt()
        val maxTileX = ceil(bounds.maxX / tileSize).toInt() - 1
        val minTileY = floor(bounds.minY / tileSize).toInt()
        val maxTileY = ceil(bounds.maxY / tileSize).toInt() - 1
        if (maxTileX < minTileX || maxTileY < minTileY) return

        val segmentStart = segmentCount
        segments.addAll(geometry.segments)
        val pathSegmentCount = geometry.segments.size / SegmentStride
        val paintIndex = paintEncoder.append(
            paint,
            command.opacity,
            command.filter,
            width,
            height,
        )
        val pathIndex = pathCount
        paths.add(segmentStart.toFloat(), pathSegmentCount.toFloat(), paintIndex.toFloat(), tileSize)
        paths.add(width, height, rasterMargin, minTileX.toFloat())
        paths.add(maxTileX.toFloat(), minTileY.toFloat(), maxTileY.toFloat(), strokeRadius)
        paths.addMatrix(transform)
        paths.add(blurRadius, spreadRadius, 0f, 0f)
        transforms += transform
        val rasterKey = cache.rasterKey(geometry.key, tileSize, rasterMargin, strokeRadius)
        rasterKeys += rasterKey
        bakedCpuPreferred = bakedCpuPreferred && cache.prefersBakedRaster(rasterKey)

        var pathCandidateCount = 0
        for (tileY in minTileY..maxTileY) {
            for (tileX in minTileX..maxTileX) {
                candidates.add(pathIndex, tileX, tileY, 0)
                pathCandidateCount++
            }
        }
        worstCaseSegmentIndexCount += pathCandidateCount.toLong() * pathSegmentCount
    }

    fun prepareCpu() {
        if (cpuPrepared) return
        vertices.clear()
        segmentIndices.clear()
        tiles.clear()
        for (pathIndex in 0 until pathCount) {
            val pathOffset = pathIndex * PathStride
            val pathSegmentStart = paths[pathOffset].toInt()
            val paintIndex = paths[pathOffset + 2].toInt()
            val cached = cache.raster(rasterKeys[pathIndex])
            if (cached != null) {
                cached.appendTo(
                    vertices,
                    segmentIndices,
                    tiles,
                    pathSegmentStart,
                    paintIndex,
                    transforms[pathIndex],
                    paths[pathOffset + EffectOffset],
                    paths[pathOffset + EffectOffset + 1],
                )
                continue
            }
            val vertexStart = vertices.size
            val segmentIndexStart = segmentIndices.size
            val tileStart = tileCount
            cpuRasterizationCount++
            prepareCpuPath(pathIndex)
            cache.storeRaster(
                rasterKeys[pathIndex],
                captureRasterTemplate(
                    vertices,
                    vertexStart,
                    segmentIndices,
                    segmentIndexStart,
                    tiles,
                    tileStart,
                    pathSegmentStart,
                ),
            )
        }
        cpuPrepared = true
    }

    fun clear() {
        segments.clear()
        paths.clear()
        candidates.clear()
        paints.clear()
        stops.clear()
        vertices.clear()
        segmentIndices.clear()
        tiles.clear()
        transforms.clear()
        rasterKeys.clear()
        worstCaseSegmentIndexCount = 0L
        cpuPrepared = false
        bakedCpuPreferred = true
    }

    fun writeSegments(destination: FloatBuffer) = segments.writeTo(destination)
    fun writeComputeInputs(destination: FloatBuffer) {
        paths.writeTo(destination)
        for (index in 0 until candidates.size) destination.put(candidates[index].toFloat())
    }
    fun writePaints(destination: FloatBuffer) = paints.writeTo(destination)
    fun writeStops(destination: FloatBuffer) = stops.writeTo(destination)
    fun writeVertices(destination: FloatBuffer) = vertices.writeTo(destination)
    fun writeSegmentIndices(destination: IntBuffer) = segmentIndices.writeTo(destination)
    fun writeTiles(destination: IntBuffer) = tiles.writeTo(destination)

    private fun prepareCpuPath(pathIndex: Int) {
        val pathOffset = pathIndex * PathStride
        val segmentStart = paths[pathOffset].toInt()
        val pathSegmentCount = paths[pathOffset + 1].toInt()
        val paintIndex = paths[pathOffset + 2].toInt()
        val tileSize = paths[pathOffset + 3]
        val aaMargin = paths[pathOffset + 6]
        val minTileX = paths[pathOffset + 7].toInt()
        val maxTileX = paths[pathOffset + 8].toInt()
        val minTileY = paths[pathOffset + 9].toInt()
        val maxTileY = paths[pathOffset + 10].toInt()
        val strokeRadius = paths[pathOffset + 11]
        val blurRadius = paths[pathOffset + EffectOffset]
        val spreadRadius = paths[pathOffset + EffectOffset + 1]
        ensureSegmentScratchCapacity(pathSegmentCount)
        val columnCount = maxTileX - minTileX + 1
        ensureBoundaryScratchCapacity(columnCount)

        for (tileY in minTileY..maxTileY) {
            val rowMinY = tileY * tileSize
            val rowMaxY = (tileY + 1) * tileSize
            if (rowMaxY <= rowMinY) continue
            Arrays.fill(boundaryColumnScratch, 0, columnCount, false)
            var rowSegmentCount = 0
            for (localIndex in 0 until pathSegmentCount) {
                val segmentIndex = segmentStart + localIndex
                val offset = segmentIndex * SegmentStride
                val y0 = segments[offset + 1]
                val y1 = segments[offset + 3]
                if (max(y0, y1) + aaMargin < rowMinY || min(y0, y1) - aaMargin > rowMaxY) continue
                rowIndexScratch[rowSegmentCount++] = segmentIndex
                val x0 = segments[offset]
                val x1 = segments[offset + 2]
                val firstColumn = floor((min(x0, x1) - aaMargin) / tileSize).toInt().coerceIn(minTileX, maxTileX)
                val lastColumn = floor((max(x0, x1) + aaMargin) / tileSize).toInt().coerceIn(minTileX, maxTileX)
                for (tileX in firstColumn..lastColumn) boundaryColumnScratch[tileX - minTileX] = true
            }
            if (rowSegmentCount == 0) continue
            appendCpuRow(
                pathIndex,
                rowMinY,
                rowMaxY,
                minTileX,
                maxTileX,
                tileSize,
                paintIndex,
                rowSegmentCount,
                strokeRadius,
                blurRadius,
                spreadRadius,
            )
        }
    }

    private fun appendCpuRow(
        pathIndex: Int,
        rowMinY: Float,
        rowMaxY: Float,
        minTileX: Int,
        maxTileX: Int,
        tileSize: Float,
        paintIndex: Int,
        rowSegmentCount: Int,
        strokeRadius: Float,
        blurRadius: Float,
        spreadRadius: Float,
    ) {
        val rowSegmentStart = segmentIndices.size
        for (index in 0 until rowSegmentCount) segmentIndices.add(rowIndexScratch[index])
        val crossingCount = if (strokeRadius > 0f) {
            0
        } else {
            buildCrossings(rowSegmentCount, (rowMinY + rowMaxY) * 0.5f).also {
                sortCrossings(crossingXScratch, crossingDeltaScratch, it)
            }
        }
        var crossingIndex = 0
        var winding = 0
        for (tileX in minTileX..maxTileX) {
            val tileMinX = tileX * tileSize
            val tileMaxX = (tileX + 1) * tileSize
            if (tileMaxX <= tileMinX) continue
            val centerX = (tileMinX + tileMaxX) * 0.5f
            while (crossingIndex < crossingCount && crossingXScratch[crossingIndex] <= centerX) {
                winding += crossingDeltaScratch[crossingIndex++]
            }
            val boundary = boundaryColumnScratch[tileX - minTileX]
            if (!boundary && (strokeRadius > 0f || winding == 0)) continue
            appendTile(
                tileMinX,
                rowMinY,
                tileMaxX,
                rowMaxY,
                transforms[pathIndex],
                if (boundary) rowSegmentStart else 0,
                if (boundary) rowSegmentCount else 0,
                paintIndex,
                !boundary,
                strokeRadius,
                blurRadius,
                spreadRadius,
            )
        }
    }

    private fun buildCrossings(rowSegmentCount: Int, y: Float): Int {
        var count = 0
        for (rowIndex in 0 until rowSegmentCount) {
            val offset = rowIndexScratch[rowIndex] * SegmentStride
            val x0 = segments[offset]
            val y0 = segments[offset + 1]
            val x1 = segments[offset + 2]
            val y1 = segments[offset + 3]
            if (!((y0 <= y && y1 > y) || (y1 <= y && y0 > y))) continue
            val progress = (y - y0) / (y1 - y0)
            crossingXScratch[count] = x0 + (x1 - x0) * progress
            crossingDeltaScratch[count] = if (y1 > y0) 1 else -1
            count++
        }
        return count
    }

    private fun appendTile(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        transform: UiMatrix4,
        segmentStart: Int,
        segmentCount: Int,
        paintIndex: Int,
        fullCoverage: Boolean,
        strokeRadius: Float,
        blurRadius: Float,
        spreadRadius: Float,
    ) {
        val recordIndex = tileCount
        tiles.add(segmentStart, segmentCount, paintIndex, if (fullCoverage) TileFullCoverage else 0)
        tiles.add(
            if (strokeRadius > 0f) TileStyleStroke else TileStyleFill,
            strokeRadius.toBits(),
            spreadRadius.toBits(),
            blurRadius.toBits(),
        )
        appendVertex(minX, minY, transform, recordIndex)
        appendVertex(minX, maxY, transform, recordIndex)
        appendVertex(maxX, maxY, transform, recordIndex)
        appendVertex(minX, minY, transform, recordIndex)
        appendVertex(maxX, maxY, transform, recordIndex)
        appendVertex(maxX, minY, transform, recordIndex)
    }

    private fun appendVertex(x: Float, y: Float, transform: UiMatrix4, recordIndex: Int) {
        val point = transform.transform(x, y)
        vertices.add(point.x, point.y, point.z, x, y, recordIndex.toFloat())
    }

    private fun invalidateCpuOutput() {
        if (!cpuPrepared) return
        vertices.clear()
        segmentIndices.clear()
        tiles.clear()
        cpuPrepared = false
    }

    private fun ensureSegmentScratchCapacity(required: Int) {
        if (rowIndexScratch.size >= required) return
        val capacity = max(required, rowIndexScratch.size * 2)
        rowIndexScratch = rowIndexScratch.copyOf(capacity)
        crossingXScratch = crossingXScratch.copyOf(capacity)
        crossingDeltaScratch = crossingDeltaScratch.copyOf(capacity)
    }

    private fun ensureBoundaryScratchCapacity(required: Int) {
        if (boundaryColumnScratch.size >= required) return
        boundaryColumnScratch = BooleanArray(max(required, boundaryColumnScratch.size * 2))
    }

    private fun sortCrossings(x: FloatArray, delta: IntArray, size: Int) {
        if (size > 1) quickSortCrossings(x, delta, 0, size - 1)
    }

    private fun quickSortCrossings(x: FloatArray, delta: IntArray, first: Int, last: Int) {
        var left = first
        var right = last
        val pivot = x[(first + last) ushr 1]
        while (left <= right) {
            while (x[left] < pivot) left++
            while (x[right] > pivot) right--
            if (left <= right) {
                val nextX = x[left]
                x[left] = x[right]
                x[right] = nextX
                val nextDelta = delta[left]
                delta[left] = delta[right]
                delta[right] = nextDelta
                left++
                right--
            }
        }
        if (first < right) quickSortCrossings(x, delta, first, right)
        if (left < last) quickSortCrossings(x, delta, left, last)
    }

    companion object {
        const val VertexStride = 6
        const val SegmentStride = 4
        const val TileStride = 8
        const val PathStride = 32
        const val CandidateStride = 4
        const val TileFullCoverage = 1
        const val TileStyleFill = 0
        const val TileStyleStroke = 1
        private const val DeviceTileSize = 16f
        private const val MinimumTileSize = 2f
        private const val MaximumTileSize = 64f
        private const val MinimumScale = 0.01f
        private const val BlurExtentFactor = 3f
        private const val EffectOffset = 28
        private const val MaxComputeCandidates = 262_144
        private const val MaxComputeSegmentIndices = 4_194_304L
    }
}
