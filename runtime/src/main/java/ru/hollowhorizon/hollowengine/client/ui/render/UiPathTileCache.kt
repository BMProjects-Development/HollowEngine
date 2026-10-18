package ru.hollowhorizon.hollowengine.client.ui.render

import ru.hollowhorizon.hollowengine.client.ui.HollowUiResourceAccess
import ru.hollowhorizon.hollowengine.client.ui.UiMatrix4
import ru.hollowhorizon.hollowengine.client.ui.shape.*
import kotlin.math.max
import kotlin.math.min

internal sealed interface UiPathGeometryMode {
    data object Fill : UiPathGeometryMode
    data object Stroke : UiPathGeometryMode

    data class StrokeOutline(
        val widthBits: Int,
        val lineCap: UiPathStrokeLineCap,
        val lineJoin: UiPathStrokeLineJoin,
    ) : UiPathGeometryMode
}

internal data class UiPathSegmentBounds(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
) {
    fun expanded(margin: Float) = UiPathSegmentBounds(
        minX - margin,
        minY - margin,
        maxX + margin,
        maxY + margin,
    )
}

internal data class UiPathGeometryKey(
    val shape: Shape,
    val widthBits: Int,
    val heightBits: Int,
    val revision: Long,
    val mode: UiPathGeometryMode,
)

internal data class UiPathGeometryTemplate(
    val key: UiPathGeometryKey,
    val segments: FloatArray,
    val bounds: UiPathSegmentBounds?,
)

internal class UiPathRasterKey(
    private val geometry: UiPathGeometryKey,
    private val tileSizeBits: Int,
    private val rasterMarginBits: Int,
    private val strokeRadiusBits: Int,
) {
    private val hash = calculateHash()

    override fun equals(other: Any?): Boolean = other is UiPathRasterKey &&
            geometry == other.geometry &&
            tileSizeBits == other.tileSizeBits &&
            rasterMarginBits == other.rasterMarginBits &&
            strokeRadiusBits == other.strokeRadiusBits

    override fun hashCode(): Int = hash

    private fun calculateHash(): Int {
        var result = geometry.hashCode()
        result = 31 * result + tileSizeBits
        result = 31 * result + rasterMarginBits
        result = 31 * result + strokeRadiusBits
        return result
    }
}

internal data class UiPathRasterTemplate(
    val vertices: FloatArray,
    val segmentIndices: IntArray,
    val tiles: IntArray,
) {
    val weight: Int get() = vertices.size + segmentIndices.size + tiles.size
}

internal class UiPathTileCache {
    private val geometry = WeightedLruCache<GeometrySourceKey, GeometrySet>(MaxGeometryScalars) {
        it.fill.segments.size + it.stroke.segments.size
    }
    private val rasters = WeightedLruCache<UiPathRasterKey, UiPathRasterTemplate>(MaxRasterScalars) { it.weight }
    private val observations = WeightedLruCache<UiPathRasterKey, Int>(MaxObservedRasters) { 1 }
    private val strokeOutlines = WeightedLruCache<UiPathGeometryKey, UiPathGeometryTemplate>(MaxGeometryScalars) {
        it.segments.size
    }

    fun geometry(shape: Shape, size: UiShapeSize, mode: UiPathGeometryMode): UiPathGeometryTemplate {
        val sourceKey = GeometrySourceKey(
            shape,
            size.width.toBits(),
            size.height.toBits(),
            shape.revision(),
        )
        val set = geometry.getOrPut(sourceKey) { buildGeometrySet(sourceKey, size) }
        return when (mode) {
            UiPathGeometryMode.Fill -> set.fill
            UiPathGeometryMode.Stroke -> set.stroke
            is UiPathGeometryMode.StrokeOutline -> strokeOutline(shape, size, mode)
        }
    }

    fun rasterKey(
        geometry: UiPathGeometryKey,
        tileSize: Float,
        rasterMargin: Float,
        strokeRadius: Float,
    ) = UiPathRasterKey(
        geometry,
        tileSize.toBits(),
        rasterMargin.toBits(),
        strokeRadius.toBits(),
    )

    fun raster(key: UiPathRasterKey): UiPathRasterTemplate? = rasters[key]

    fun prefersBakedRaster(key: UiPathRasterKey): Boolean {
        if (rasters[key] != null) return true
        val count = ((observations[key] ?: 0) + 1).coerceAtMost(RequiredObservations)
        observations[key] = count
        return count >= RequiredObservations
    }

    fun storeRaster(key: UiPathRasterKey, raster: UiPathRasterTemplate) {
        rasters[key] = raster
        observations.remove(key)
    }

    private fun buildGeometrySet(sourceKey: GeometrySourceKey, size: UiShapeSize): GeometrySet {
        val contours = sourceKey.shape.createPath(size).flatten().contours
        val fillSegments = UiFloatArrayBuilder()
        val strokeSegments = UiFloatArrayBuilder()
        appendFillSegments(contours, fillSegments)
        appendStrokeSegments(contours, strokeSegments)
        return GeometrySet(
            UiPathGeometryTemplate(
                UiPathGeometryKey(
                    sourceKey.shape,
                    sourceKey.widthBits,
                    sourceKey.heightBits,
                    sourceKey.revision,
                    UiPathGeometryMode.Fill,
                ),
                fillSegments.copyRange(0),
                segmentBounds(fillSegments),
            ),
            UiPathGeometryTemplate(
                UiPathGeometryKey(
                    sourceKey.shape,
                    sourceKey.widthBits,
                    sourceKey.heightBits,
                    sourceKey.revision,
                    UiPathGeometryMode.Stroke,
                ),
                strokeSegments.copyRange(0),
                segmentBounds(strokeSegments),
            ),
        )
    }

    private fun strokeOutline(
        shape: Shape,
        size: UiShapeSize,
        mode: UiPathGeometryMode.StrokeOutline,
    ): UiPathGeometryTemplate {
        val key = UiPathGeometryKey(
            shape,
            size.width.toBits(),
            size.height.toBits(),
            shape.revision(),
            mode,
        )
        return strokeOutlines.getOrPut(key) {
            val contours = shape.createPath(size).strokedPath(
                Float.fromBits(mode.widthBits),
                mode.lineCap,
                mode.lineJoin,
            ).flatten().contours
            val segments = UiFloatArrayBuilder()
            appendFillSegments(contours, segments)
            UiPathGeometryTemplate(key, segments.copyRange(0), segmentBounds(segments))
        }
    }

    private data class GeometrySourceKey(
        val shape: Shape,
        val widthBits: Int,
        val heightBits: Int,
        val revision: Long,
    )

    private data class GeometrySet(
        val fill: UiPathGeometryTemplate,
        val stroke: UiPathGeometryTemplate,
    )

    private companion object {
        const val MaxGeometryScalars = 1_048_576
        const val MaxRasterScalars = 4_194_304
        const val MaxObservedRasters = 4_096
        const val RequiredObservations = 2
    }
}

private fun Shape.revision(): Long =
    if (this is SvgResourceShape) HollowUiResourceAccess.version(location) else 0L

internal fun captureRasterTemplate(
    vertices: UiFloatArrayBuilder,
    vertexStart: Int,
    segmentIndices: UiIntArrayBuilder,
    segmentIndexStart: Int,
    tiles: UiIntArrayBuilder,
    tileStart: Int,
    pathSegmentStart: Int,
): UiPathRasterTemplate {
    val cachedVertices = vertices.copyRange(vertexStart)
    var vertexOffset = UiPathTileBatch.VertexStride - 1
    while (vertexOffset < cachedVertices.size) {
        cachedVertices[vertexOffset - 5] = cachedVertices[vertexOffset - 2]
        cachedVertices[vertexOffset - 4] = cachedVertices[vertexOffset - 1]
        cachedVertices[vertexOffset - 3] = 0f
        cachedVertices[vertexOffset] -= tileStart.toFloat()
        vertexOffset += UiPathTileBatch.VertexStride
    }

    val cachedIndices = segmentIndices.copyRange(segmentIndexStart)
    for (index in cachedIndices.indices) cachedIndices[index] -= pathSegmentStart

    val cachedTiles = tiles.copyRange(tileStart * UiPathTileBatch.TileStride)
    var tileOffset = 0
    while (tileOffset < cachedTiles.size) {
        cachedTiles[tileOffset] = if (cachedTiles[tileOffset + 1] > 0) {
            cachedTiles[tileOffset] - segmentIndexStart
        } else {
            0
        }
        cachedTiles[tileOffset + 2] = 0
        tileOffset += UiPathTileBatch.TileStride
    }
    return UiPathRasterTemplate(cachedVertices, cachedIndices, cachedTiles)
}

internal fun UiPathRasterTemplate.appendTo(
    vertices: UiFloatArrayBuilder,
    segmentIndices: UiIntArrayBuilder,
    tiles: UiIntArrayBuilder,
    pathSegmentStart: Int,
    paintIndex: Int,
    transform: UiMatrix4,
    blurRadius: Float,
    spreadRadius: Float,
) {
    val tileStart = tiles.size / UiPathTileBatch.TileStride
    val segmentIndexStart = segmentIndices.size
    var vertexOffset = 0
    while (vertexOffset < this.vertices.size) {
        val localX = this.vertices[vertexOffset + 3]
        val localY = this.vertices[vertexOffset + 4]
        val point = transform.transform(localX, localY)
        vertices.add(
            point.x,
            point.y,
            point.z,
            localX,
            localY,
            this.vertices[vertexOffset + 5] + tileStart,
        )
        vertexOffset += UiPathTileBatch.VertexStride
    }
    for (segmentIndex in this.segmentIndices) segmentIndices.add(segmentIndex + pathSegmentStart)
    var tileOffset = 0
    while (tileOffset < this.tiles.size) {
        val segmentCount = this.tiles[tileOffset + 1]
        tiles.add(
            if (segmentCount > 0) this.tiles[tileOffset] + segmentIndexStart else 0,
            segmentCount,
            paintIndex,
            this.tiles[tileOffset + 3],
        )
        tiles.add(
            this.tiles[tileOffset + 4],
            this.tiles[tileOffset + 5],
            spreadRadius.toBits(),
            blurRadius.toBits(),
        )
        tileOffset += UiPathTileBatch.TileStride
    }
}

private class WeightedLruCache<K, V>(
    private val maxWeight: Int,
    private val weightOf: (V) -> Int,
) {
    private val values = LinkedHashMap<K, V>(16, 0.75f, true)
    private var weight = 0

    operator fun get(key: K): V? = values[key]

    operator fun set(key: K, value: V) {
        val valueWeight = weightOf(value)
        if (valueWeight > maxWeight) return
        values.put(key, value)?.let { weight -= weightOf(it) }
        weight += valueWeight
        val iterator = values.entries.iterator()
        while (weight > maxWeight && iterator.hasNext()) {
            weight -= weightOf(iterator.next().value)
            iterator.remove()
        }
    }

    fun remove(key: K) {
        values.remove(key)?.let { weight -= weightOf(it) }
    }

    fun getOrPut(key: K, create: () -> V): V {
        values[key]?.let { return it }
        return create().also { this[key] = it }
    }
}

private fun appendFillSegments(contours: List<UiPathContour>, destination: UiFloatArrayBuilder) {
    for (contour in contours) {
        if (contour.points.size < 3) continue
        for (index in contour.points.indices) {
            appendSegment(contour.points[index], contour.points[(index + 1) % contour.points.size], destination)
        }
    }
}

private fun appendStrokeSegments(contours: List<UiPathContour>, destination: UiFloatArrayBuilder) {
    for (contour in contours) {
        if (contour.points.size < 2) continue
        for (index in 0 until contour.points.lastIndex) {
            appendSegment(contour.points[index], contour.points[index + 1], destination)
        }
        if (contour.closed) appendSegment(contour.points.last(), contour.points.first(), destination)
    }
}

private fun appendSegment(first: UiPathPoint, second: UiPathPoint, destination: UiFloatArrayBuilder) {
    if (first != second) destination.add(first.x, first.y, second.x, second.y)
}

private fun segmentBounds(source: UiFloatArrayBuilder): UiPathSegmentBounds? {
    if (source.size == 0) return null
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var offset = 0
    while (offset < source.size) {
        minX = min(minX, min(source[offset], source[offset + 2]))
        minY = min(minY, min(source[offset + 1], source[offset + 3]))
        maxX = max(maxX, max(source[offset], source[offset + 2]))
        maxY = max(maxY, max(source[offset + 1], source[offset + 3]))
        offset += UiPathTileBatch.SegmentStride
    }
    return UiPathSegmentBounds(minX, minY, maxX, maxY)
}
