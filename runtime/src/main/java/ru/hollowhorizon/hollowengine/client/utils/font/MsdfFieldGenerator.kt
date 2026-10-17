package ru.hollowhorizon.hollowengine.client.utils.font

import kotlin.math.abs
import kotlin.math.sqrt

/** Scratch results of one edge's distance query, avoiding an allocation per pixel per edge. */
private class EdgeDistance {
    var distance = 0f
    var dot = 0f
    var param = 0f
}

/**
 * Renders the field with msdfgen's perpendicular-distance selector and overlapping-contour
 * combiner, ported faithfully because every simplification of them shows up on screen.
 */
internal fun generateMsdf(
    shape: MsdfShape,
    width: Int,
    height: Int,
    scale: Float,
    translateX: Float,
    translateY: Float,
    range: Float,
    output: FloatArray,
) {
    MsdfFieldGenerator(shape).generate(width, height, scale, translateX, translateY, range, output)
}

private class MsdfFieldGenerator(shape: MsdfShape) {
    private val edges = ArrayList<MsdfEdge>()
    private val geometry: FloatArray
    private val cache: FloatArray
    private val contourOfEdge: IntArray
    private val contourCount: Int
    private val windings: IntArray
    private val selector: FloatArray
    private val nearEdge: IntArray
    private val merged = FloatArray(3 * 3 * SelectorStride)
    private val mergedNear = IntArray(3 * 3)
    private val contourDistance: FloatArray
    private val contourMedian: FloatArray
    private val chosen = FloatArray(3)
    private val shapeDistance = FloatArray(3)
    private val innerDistance = FloatArray(3)
    private val outerDistance = FloatArray(3)

    private val scratch = EdgeDistance()
    private var lastX = 0f
    private var lastY = 0f

    init {
        val contours = shape.contours.filter { it.edges.isNotEmpty() }
        contourCount = contours.size
        windings = IntArray(contourCount)
        contourOfEdge = IntArray(contours.sumOf { it.edges.size })
        edges.ensureCapacity(contourOfEdge.size)
        for ((index, contour) in contours.withIndex()) {
            val area = contour.doubleSignedArea()
            windings[index] = if (area < 0f) 1 else if (area > 0f) -1 else 0
            for (edge in contour.edges) {
                contourOfEdge[edges.size] = index
                edges += edge
            }
        }
        selector = FloatArray(contourCount * 3 * SelectorStride)
        nearEdge = IntArray(contourCount * 3)
        contourDistance = FloatArray(contourCount * 3)
        contourMedian = FloatArray(contourCount)
        geometry = FloatArray(edges.size * GeometryStride)
        cache = FloatArray(edges.size * CacheStride)
        var index = 0
        for (contour in contours) {
            val count = contour.edges.size
            for (position in 0 until count) {
                val edge = contour.edges[position]
                val previous = contour.edges[(position + count - 1) % count]
                val next = contour.edges[(position + 1) % count]
                val base = index * GeometryStride
                writeUnitVector(base + GeoADirX, edge.startDirX, edge.startDirY)
                writeUnitVector(base + GeoBDirX, edge.endDirX, edge.endDirY)
                val previousLength = length(previous.endDirX, previous.endDirY)
                val nextLength = length(next.startDirX, next.startDirY)
                writeUnitVector(
                    base + GeoABisX,
                    geometry[base + GeoADirX] + if (previousLength > 0f) previous.endDirX / previousLength else 0f,
                    geometry[base + GeoADirY] + if (previousLength > 0f) previous.endDirY / previousLength else 0f,
                )
                writeUnitVector(
                    base + GeoBBisX,
                    geometry[base + GeoBDirX] + if (nextLength > 0f) next.startDirX / nextLength else 0f,
                    geometry[base + GeoBDirY] + if (nextLength > 0f) next.startDirY / nextLength else 0f,
                )
                index++
            }
        }
    }

    private fun writeUnitVector(at: Int, x: Float, y: Float) {
        val length = length(x, y)
        geometry[at] = if (length > 0f) x / length else 0f
        geometry[at + 1] = if (length > 0f) y / length else 0f
    }

    private fun length(x: Float, y: Float): Float = sqrt(x * x + y * y)

    fun generate(
        width: Int,
        height: Int,
        scale: Float,
        translateX: Float,
        translateY: Float,
        range: Float,
        output: FloatArray,
    ) {
        for (block in 0 until contourCount * 3) {
            selector[block * SelectorStride] = -Float.MAX_VALUE
        }
        for (row in 0 until height) {
            val sampleY = (row + 0.5f) / scale - translateY
            for (column in 0 until width) {
                val sampleX = (column + 0.5f) / scale - translateX
                samplePoint(sampleX, sampleY)
                combine(sampleX, sampleY)
                val offset = (row * width + column) * 3
                output[offset] = chosen[0] / range + 0.5f
                output[offset + 1] = chosen[1] / range + 0.5f
                output[offset + 2] = chosen[2] / range + 0.5f
            }
        }
    }

    private fun samplePoint(x: Float, y: Float) {
        val moveX = x - lastX
        val moveY = y - lastY
        val resetDelta = DistanceDeltaFactor * sqrt(moveX * moveX + moveY * moveY)
        lastX = x
        lastY = y
        for (block in 0 until contourCount * 3) {
            val base = block * SelectorStride
            val trueDist = selector[base + SelTrueDistance]
            val loosened = trueDist + (if (trueDist > 0f) resetDelta else -resetDelta)
            selector[base] = loosened
            selector[base + SelMinNegativePerp] = -abs(loosened)
            selector[base + SelMinPositivePerp] = abs(loosened)
            nearEdge[block] = -1
        }
        for (index in edges.indices) {
            val edge = edges[index]
            val color = edge.color
            val block = contourOfEdge[index] * 3
            val cacheBase = index * CacheStride
            val cachedX = cache[cacheBase + CacheX]
            val cachedY = cache[cacheBase + CacheY]
            val dx = x - cachedX
            val dy = y - cachedY
            val delta = DistanceDeltaFactor * sqrt(dx * dx + dy * dy)
            val relevant = (color and MsdfChannel.RED != 0 && isRelevant(block, cacheBase, delta)) ||
                    (color and MsdfChannel.GREEN != 0 && isRelevant(block + 1, cacheBase, delta)) ||
                    (color and MsdfChannel.BLUE != 0 && isRelevant(block + 2, cacheBase, delta))
            if (!relevant) continue
            addEdge(index, x, y)
        }
    }

    private fun isRelevant(block: Int, cacheBase: Int, delta: Float): Boolean {
        val base = block * SelectorStride
        if (cache[cacheBase + CacheDistance] - delta <= abs(selector[base + SelTrueDistance])) return true
        val aDomain = cache[cacheBase + CacheADomain]
        val bDomain = cache[cacheBase + CacheBDomain]
        if (abs(aDomain) < delta || abs(bDomain) < delta) return true
        if (aDomain > 0f) {
            val aPerp = cache[cacheBase + CacheAPerp]
            val fits = if (aPerp < 0f) aPerp + delta >= selector[base + SelMinNegativePerp] else aPerp - delta <= selector[base + SelMinPositivePerp]
            if (fits) return true
        }
        if (bDomain > 0f) {
            val bPerp = cache[cacheBase + CacheBPerp]
            val fits = if (bPerp < 0f) bPerp + delta >= selector[base + SelMinNegativePerp] else bPerp - delta <= selector[base + SelMinPositivePerp]
            if (fits) return true
        }
        return false
    }

    private fun addEdge(index: Int, x: Float, y: Float) {
        val edge = edges[index]
        edge.signedDistance(x, y, scratch)
        val distance = scratch.distance
        val color = edge.color
        val block = contourOfEdge[index] * 3
        if (color and MsdfChannel.RED != 0) addTrueDistance(block, index, distance, scratch.dot, scratch.param)
        if (color and MsdfChannel.GREEN != 0) addTrueDistance(block + 1, index, distance, scratch.dot, scratch.param)
        if (color and MsdfChannel.BLUE != 0) addTrueDistance(block + 2, index, distance, scratch.dot, scratch.param)

        val cacheBase = index * CacheStride
        cache[cacheBase] = x
        cache[cacheBase + CacheY] = y
        cache[cacheBase + CacheDistance] = abs(distance)

        val geometryBase = index * GeometryStride
        val apX = x - edge.startX
        val apY = y - edge.startY
        val bpX = x - edge.endX
        val bpY = y - edge.endY
        val aDomain = apX * geometry[geometryBase + GeoABisX] + apY * geometry[geometryBase + GeoABisY]
        val bDomain = -(bpX * geometry[geometryBase + GeoBBisX] + bpY * geometry[geometryBase + GeoBBisY])
        if (aDomain > 0f) {
            var pd = distance
            val dirX = -geometry[geometryBase + GeoADirX]
            val dirY = -geometry[geometryBase + GeoADirY]
            if (apX * dirX + apY * dirY > 0f) {
                val perp = apX * dirY - apY * dirX
                if (abs(perp) < abs(pd)) {
                    pd = -perp
                    addPerpendicular(color, block, pd)
                }
            }
            cache[cacheBase + CacheAPerp] = pd
        }
        if (bDomain > 0f) {
            var pd = distance
            val dirX = geometry[geometryBase + GeoBDirX]
            val dirY = geometry[geometryBase + GeoBDirY]
            if (bpX * dirX + bpY * dirY > 0f) {
                val perp = bpX * dirY - bpY * dirX
                if (abs(perp) < abs(pd)) {
                    pd = perp
                    addPerpendicular(color, block, pd)
                }
            }
            cache[cacheBase + CacheBPerp] = pd
        }
        cache[cacheBase + CacheADomain] = aDomain
        cache[cacheBase + CacheBDomain] = bDomain
    }

    private fun addTrueDistance(block: Int, index: Int, distance: Float, dot: Float, param: Float) {
        val base = block * SelectorStride
        val currentAbs = abs(selector[base + SelTrueDistance])
        val candidateAbs = abs(distance)
        if (candidateAbs < currentAbs || (candidateAbs == currentAbs && dot < selector[base + SelDot])) {
            selector[base] = distance
            selector[base + SelDot] = dot
            selector[base + SelParam] = param
            nearEdge[block] = index
        }
    }

    private fun addPerpendicular(color: Int, block: Int, distance: Float) {
        if (color and MsdfChannel.RED != 0) addPerpendicularChannel(block, distance)
        if (color and MsdfChannel.GREEN != 0) addPerpendicularChannel(block + 1, distance)
        if (color and MsdfChannel.BLUE != 0) addPerpendicularChannel(block + 2, distance)
    }

    private fun addPerpendicularChannel(block: Int, distance: Float) {
        val base = block * SelectorStride
        if (distance <= 0f && distance > selector[base + SelMinNegativePerp]) selector[base + SelMinNegativePerp] = distance
        if (distance >= 0f && distance < selector[base + SelMinPositivePerp]) selector[base + SelMinPositivePerp] = distance
    }

    private fun computeDistance(sel: FloatArray, near: IntArray, block: Int, x: Float, y: Float): Float {
        val base = block * SelectorStride
        val trueDist = sel[base + SelTrueDistance]
        var distance = if (trueDist < 0f) sel[base + SelMinNegativePerp] else sel[base + SelMinPositivePerp]
        val nearIndex = near[block]
        if (nearIndex >= 0) {
            var converted = trueDist
            val param = sel[base + SelParam]
            val edge = edges[nearIndex]
            val geometryBase = nearIndex * GeometryStride
            if (param < 0f) {
                val dirX = geometry[geometryBase + GeoADirX]
                val dirY = geometry[geometryBase + GeoADirY]
                val aqX = x - edge.startX
                val aqY = y - edge.startY
                if (aqX * dirX + aqY * dirY < 0f) {
                    val perp = aqX * dirY - aqY * dirX
                    if (abs(perp) <= abs(converted)) converted = perp
                }
            } else if (param > 1f) {
                val dirX = geometry[geometryBase + GeoBDirX]
                val dirY = geometry[geometryBase + GeoBDirY]
                val bqX = x - edge.endX
                val bqY = y - edge.endY
                if (bqX * dirX + bqY * dirY > 0f) {
                    val perp = bqX * dirY - bqY * dirX
                    if (abs(perp) <= abs(converted)) converted = perp
                }
            }
            if (abs(converted) < abs(distance)) distance = converted
        }
        return distance
    }

    private fun combine(x: Float, y: Float) {
        if (contourCount == 1) {
            chosen[0] = computeDistance(selector, nearEdge, 0, x, y)
            chosen[1] = computeDistance(selector, nearEdge, 1, x, y)
            chosen[2] = computeDistance(selector, nearEdge, 2, x, y)
            return
        }
        for (contour in 0 until contourCount) {
            for (channel in 0 until 3) {
                contourDistance[contour * 3 + channel] =
                    computeDistance(selector, nearEdge, contour * 3 + channel, x, y)
            }
            contourMedian[contour] = medianOf(contourDistance, contour * 3)
        }
        for (block in 0 until 9) {
            val base = block * SelectorStride
            merged[base + SelTrueDistance] = -Float.MAX_VALUE
            merged[base + SelDot] = 0f
            merged[base + SelParam] = 0f
            merged[base + SelMinNegativePerp] = -Float.MAX_VALUE
            merged[base + SelMinPositivePerp] = Float.MAX_VALUE
            mergedNear[block] = -1
        }
        for (contour in 0 until contourCount) {
            mergeContour(ShapeSet, contour)
            if (windings[contour] > 0 && contourMedian[contour] >= 0f) mergeContour(InnerSet, contour)
            if (windings[contour] < 0 && contourMedian[contour] <= 0f) mergeContour(OuterSet, contour)
        }
        val shapeMedian = resolveMerged(ShapeSet, shapeDistance, x, y)
        val innerMedian = resolveMerged(InnerSet, innerDistance, x, y)
        val outerMedian = resolveMerged(OuterSet, outerDistance, x, y)

        val winding: Int
        if (innerMedian >= 0f && abs(innerMedian) <= abs(outerMedian)) {
            innerDistance.copyInto(chosen)
            winding = 1
            for (contour in 0 until contourCount) {
                if (windings[contour] <= 0) continue
                val contourMed = contourMedian[contour]
                if (abs(contourMed) < abs(outerMedian) && contourMed > medianOf(chosen, 0)) {
                    copyTriple(contour)
                }
            }
        } else if (outerMedian <= 0f && abs(outerMedian) < abs(innerMedian)) {
            outerDistance.copyInto(chosen)
            winding = -1
            for (contour in 0 until contourCount) {
                if (windings[contour] >= 0) continue
                val contourMed = contourMedian[contour]
                if (abs(contourMed) < abs(innerMedian) && contourMed < medianOf(chosen, 0)) {
                    copyTriple(contour)
                }
            }
        } else {
            shapeDistance.copyInto(chosen)
            return
        }
        for (contour in 0 until contourCount) {
            if (windings[contour] == winding) continue
            val contourMed = contourMedian[contour]
            val chosenMed = medianOf(chosen, 0)
            if (contourMed * chosenMed >= 0f && abs(contourMed) < abs(chosenMed)) {
                copyTriple(contour)
            }
        }
        if (medianOf(chosen, 0) == shapeMedian) shapeDistance.copyInto(chosen)
    }

    private fun resolveMerged(set: Int, out: FloatArray, x: Float, y: Float): Float {
        for (channel in 0 until 3) {
            out[channel] = computeDistance(merged, mergedNear, set * 3 + channel, x, y)
        }
        return medianOf(out, 0)
    }

    private fun copyTriple(contour: Int) {
        contourDistance.copyInto(chosen, 0, contour * 3, contour * 3 + 3)
    }

    private fun mergeContour(set: Int, contour: Int) {
        for (channel in 0 until 3) {
            val dst = (set * 3 + channel) * SelectorStride
            val src = (contour * 3 + channel) * SelectorStride
            val dstAbs = abs(merged[dst + SelTrueDistance])
            val srcAbs = abs(selector[src + SelTrueDistance])
            if (srcAbs < dstAbs || (srcAbs == dstAbs && selector[src + SelDot] < merged[dst + SelDot])) {
                merged[dst] = selector[src + SelTrueDistance]
                merged[dst + SelDot] = selector[src + SelDot]
                merged[dst + SelParam] = selector[src + SelParam]
                mergedNear[set * 3 + channel] = nearEdge[contour * 3 + channel]
            }
            if (selector[src + SelMinNegativePerp] > merged[dst + SelMinNegativePerp]) merged[dst + SelMinNegativePerp] = selector[src + SelMinNegativePerp]
            if (selector[src + SelMinPositivePerp] < merged[dst + SelMinPositivePerp]) merged[dst + SelMinPositivePerp] = selector[src + SelMinPositivePerp]
        }
    }

    private fun medianOf(values: FloatArray, base: Int): Float =
        median(values[base], values[base + 1], values[base + 2])

    private fun median(a: Float, b: Float, c: Float): Float = msdfMedian(a, b, c)

    private companion object {
        const val DistanceDeltaFactor = 1.001f

        const val GeometryStride = 8
        const val GeoADirX = 0
        const val GeoADirY = 1
        const val GeoBDirX = 2
        const val GeoBDirY = 3
        const val GeoABisX = 4
        const val GeoABisY = 5
        const val GeoBBisX = 6
        const val GeoBBisY = 7

        const val CacheStride = 7
        const val CacheX = 0
        const val CacheY = 1
        const val CacheDistance = 2
        const val CacheADomain = 3
        const val CacheBDomain = 4
        const val CacheAPerp = 5
        const val CacheBPerp = 6

        const val SelectorStride = 5
        const val SelTrueDistance = 0
        const val SelDot = 1
        const val SelParam = 2
        const val SelMinNegativePerp = 3
        const val SelMinPositivePerp = 4

        const val ShapeSet = 0
        const val InnerSet = 1
        const val OuterSet = 2
    }
}

private fun MsdfEdge.signedDistance(x: Float, y: Float, out: EdgeDistance) {
    var bestDistance = Float.MAX_VALUE
    var bestAbsolute = Float.MAX_VALUE
    var bestDot = 0f
    var bestParam = 0f
    val segments = subSegments
    for (segment in 0 until segments) {
        val index = segment * 2
        val ax = points[index]
        val ay = points[index + 1]
        val abx = points[index + 2] - ax
        val aby = points[index + 3] - ay
        val abLengthSquared = abx * abx + aby * aby
        if (abLengthSquared == 0f) continue
        val aqx = x - ax
        val aqy = y - ay
        val param = (aqx * abx + aqy * aby) / abLengthSquared
        val towardsEnd = param > 0.5f
        val eqx = (if (towardsEnd) points[index + 2] else ax) - x
        val eqy = (if (towardsEnd) points[index + 3] else ay) - y
        val endpointDistance = sqrt(eqx * eqx + eqy * eqy)
        var distance: Float
        var dot: Float
        val abLength = sqrt(abLengthSquared)
        val orthogonal = (aby * aqx - abx * aqy) / abLength
        if (param > 0f && param < 1f && abs(orthogonal) < endpointDistance) {
            distance = orthogonal
            dot = 0f
        } else {
            val cross = aqx * aby - aqy * abx
            distance = if (cross >= 0f) endpointDistance else -endpointDistance
            dot = if (endpointDistance == 0f) {
                0f
            } else {
                abs((abx * eqx + aby * eqy) / (abLength * endpointDistance))
            }
        }
        val absolute = abs(distance)
        if (absolute < bestAbsolute || (absolute == bestAbsolute && dot < bestDot)) {
            bestAbsolute = absolute
            bestDistance = distance
            bestDot = dot
            bestParam = (segment + param) / segments
        }
    }
    out.distance = bestDistance
    out.dot = bestDot
    out.param = bestParam
}
