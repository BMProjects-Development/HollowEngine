package ru.hollowhorizon.hollowengine.client.utils.font

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

internal fun correctMsdfField(
    field: FloatArray,
    width: Int,
    height: Int,
    shape: MsdfShape,
    scale: Float,
    translateX: Float,
    translateY: Float,
    pixelRange: Float,
) {
    correctMsdfSigns(field, width, height, shape, scale, translateX, translateY)
    correctMsdfErrors(field, width, height, shape, scale, translateX, translateY, pixelRange)
}

internal fun correctMsdfErrors(
    field: FloatArray,
    width: Int,
    height: Int,
    shape: MsdfShape,
    scale: Float,
    translateX: Float,
    translateY: Float,
    pixelRange: Float,
) {
    if (width <= 0 || height <= 0 || pixelRange <= 0f) return
    val stencil = IntArray(width * height)
    val texelStep = 1f / pixelRange
    protectCorners(stencil, width, height, shape, scale, translateX, translateY)
    protectEdges(stencil, field, width, height, texelStep)
    findErrors(stencil, field, width, height, texelStep)
    applyErrors(stencil, field, width, height)
}

internal fun correctMsdfSigns(
    field: FloatArray,
    width: Int,
    height: Int,
    shape: MsdfShape,
    scale: Float,
    translateX: Float,
    translateY: Float,
) {
    if (width <= 0 || height <= 0) return
    val match = ByteArray(width * height)
    var ambiguous = false
    val crossings = ScanlineCrossings(shape.contours.sumOf { contour -> contour.edges.sumOf { it.subSegments } })
    for (y in 0 until height) {
        val sampleY = (y + 0.5f) / scale - translateY
        crossings.collect(shape, sampleY)
        for (x in 0 until width) {
            val sampleX = (x + 0.5f) / scale - translateX
            val fill = crossings.windingRightOf(sampleX) != 0
            val texel = y * width + x
            val base = texel * 3
            val median = median(field[base], field[base + 1], field[base + 2])
            when {
                median == 0.5f -> ambiguous = true
                (median > 0.5f) != fill -> {
                    field[base] = 1f - field[base]
                    field[base + 1] = 1f - field[base + 1]
                    field[base + 2] = 1f - field[base + 2]
                    match[texel] = -1
                }

                else -> match[texel] = 1
            }
        }
    }
    if (!ambiguous) return
    for (y in 0 until height) {
        for (x in 0 until width) {
            val texel = y * width + x
            if (match[texel] != 0.toByte()) continue
            var neighbours = 0
            if (x > 0) neighbours += match[texel - 1]
            if (x + 1 < width) neighbours += match[texel + 1]
            if (y > 0) neighbours += match[texel - width]
            if (y + 1 < height) neighbours += match[texel + width]
            if (neighbours < 0) {
                val base = texel * 3
                field[base] = 1f - field[base]
                field[base + 1] = 1f - field[base + 1]
                field[base + 2] = 1f - field[base + 2]
            }
        }
    }
}

private class ScanlineCrossings(capacity: Int) {
    private val xs = FloatArray(capacity)
    private val directions = IntArray(capacity)
    private var count = 0

    fun collect(shape: MsdfShape, sampleY: Float) {
        count = 0
        for (contour in shape.contours) {
            for (edge in contour.edges) {
                val points = edge.points
                var index = 0
                while (index + 3 < points.size) {
                    val y0 = points[index + 1]
                    val y1 = points[index + 3]
                    val direction = when {
                        y0 <= sampleY && y1 > sampleY -> 1
                        y1 <= sampleY && y0 > sampleY -> -1
                        else -> 0
                    }
                    if (direction != 0) {
                        val t = (sampleY - y0) / (y1 - y0)
                        xs[count] = points[index] + t * (points[index + 2] - points[index])
                        directions[count] = direction
                        count++
                    }
                    index += 2
                }
            }
        }
    }

    fun windingRightOf(x: Float): Int {
        var winding = 0
        for (index in 0 until count) {
            if (xs[index] > x) winding += directions[index]
        }
        return winding
    }
}

private const val ProtectedFlag = 1
private const val ErrorFlag = 2

private const val ArtifactTEpsilon = 0.01

private const val ProtectionRadiusTolerance = 1.001f

private const val MinDeviationRatio = 1.111111f

private fun median(a: Float, b: Float, c: Float): Float = msdfMedian(a, b, c)

private fun mix(a: Float, b: Float, weight: Double): Float = (a + (b - a) * weight).toFloat()

private fun protectCorners(
    stencil: IntArray,
    width: Int,
    height: Int,
    shape: MsdfShape,
    scale: Float,
    translateX: Float,
    translateY: Float,
) {
    for (contour in shape.contours) {
        if (contour.edges.isEmpty()) continue
        var previous = contour.edges.last()
        for (edge in contour.edges) {
            val common = previous.color and edge.color
            if (common and (common - 1) == 0) {
                val x = (edge.startX + translateX) * scale
                val y = (edge.startY + translateY) * scale
                val left = floor(x - 0.5f).toInt()
                val bottom = floor(y - 0.5f).toInt()
                markProtected(stencil, width, height, left, bottom)
                markProtected(stencil, width, height, left + 1, bottom)
                markProtected(stencil, width, height, left, bottom + 1)
                markProtected(stencil, width, height, left + 1, bottom + 1)
            }
            previous = edge
        }
    }
}

private fun markProtected(stencil: IntArray, width: Int, height: Int, x: Int, y: Int) {
    if (x < 0 || y < 0 || x >= width || y >= height) return
    stencil[y * width + x] = stencil[y * width + x] or ProtectedFlag
}

private fun edgeBetweenTexelsChannel(field: FloatArray, a: Int, b: Int, channel: Int): Boolean {
    val start = field[a + channel]
    val end = field[b + channel]
    val t = (start - 0.5) / (start - end)
    if (t <= 0.0 || t >= 1.0) return false
    val red = mix(field[a], field[b], t)
    val green = mix(field[a + 1], field[b + 1], t)
    val blue = mix(field[a + 2], field[b + 2], t)
    return median(red, green, blue) == when (channel) {
        0 -> red
        1 -> green
        else -> blue
    }
}

private fun edgeBetweenTexels(field: FloatArray, a: Int, b: Int): Int {
    var mask = 0
    if (edgeBetweenTexelsChannel(field, a, b, 0)) mask = mask or MsdfChannel.RED
    if (edgeBetweenTexelsChannel(field, a, b, 1)) mask = mask or MsdfChannel.GREEN
    if (edgeBetweenTexelsChannel(field, a, b, 2)) mask = mask or MsdfChannel.BLUE
    return mask
}

private fun protectExtremeChannels(
    stencil: IntArray,
    texel: Int,
    field: FloatArray,
    base: Int,
    median: Float,
    mask: Int,
) {
    val extreme = (mask and MsdfChannel.RED != 0 && field[base] != median) ||
            (mask and MsdfChannel.GREEN != 0 && field[base + 1] != median) ||
            (mask and MsdfChannel.BLUE != 0 && field[base + 2] != median)
    if (extreme) stencil[texel] = stencil[texel] or ProtectedFlag
}

private fun protectEdges(stencil: IntArray, field: FloatArray, width: Int, height: Int, texelStep: Float) {
    val straightRadius = ProtectionRadiusTolerance * texelStep
    val diagonalRadius = straightRadius * sqrt(2f)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val texel = y * width + x
            val base = texel * 3
            val median = median(field[base], field[base + 1], field[base + 2])
            if (x + 1 < width) {
                protectPair(stencil, field, texel, texel + 1, median, straightRadius)
            }
            if (y + 1 < height) {
                protectPair(stencil, field, texel, texel + width, median, straightRadius)
            }
            if (x + 1 < width && y + 1 < height) {
                protectPair(stencil, field, texel, texel + width + 1, median, diagonalRadius)
                val rightBase = (texel + 1) * 3
                val rightMedian = median(field[rightBase], field[rightBase + 1], field[rightBase + 2])
                protectPair(stencil, field, texel + 1, texel + width, rightMedian, diagonalRadius)
            }
        }
    }
}

private fun protectPair(
    stencil: IntArray,
    field: FloatArray,
    first: Int,
    second: Int,
    firstMedian: Float,
    radius: Float,
) {
    val firstBase = first * 3
    val secondBase = second * 3
    val secondMedian = median(field[secondBase], field[secondBase + 1], field[secondBase + 2])
    if (abs(firstMedian - 0.5f) + abs(secondMedian - 0.5f) >= radius) return
    val mask = edgeBetweenTexels(field, firstBase, secondBase)
    protectExtremeChannels(stencil, first, field, firstBase, firstMedian, mask)
    protectExtremeChannels(stencil, second, field, secondBase, secondMedian, mask)
}

private fun findErrors(stencil: IntArray, field: FloatArray, width: Int, height: Int, texelStep: Float) {
    val roots = DoubleArray(2)
    val straightSpan = (MinDeviationRatio * texelStep).toDouble()
    val diagonalSpan = straightSpan * sqrt(2.0)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val texel = y * width + x
            val base = texel * 3
            val median = median(field[base], field[base + 1], field[base + 2])
            val isProtected = stencil[texel] and ProtectedFlag != 0
            val left = if (x > 0) (texel - 1) * 3 else -1
            val right = if (x + 1 < width) (texel + 1) * 3 else -1
            val below = if (y > 0) (texel - width) * 3 else -1
            val above = if (y + 1 < height) (texel + width) * 3 else -1
            val artifact =
                (left >= 0 && hasLinearArtifact(straightSpan, isProtected, median, field, base, left)) ||
                        (below >= 0 && hasLinearArtifact(straightSpan, isProtected, median, field, base, below)) ||
                        (right >= 0 && hasLinearArtifact(straightSpan, isProtected, median, field, base, right)) ||
                        (above >= 0 && hasLinearArtifact(straightSpan, isProtected, median, field, base, above)) ||
                        (left >= 0 && below >= 0 && hasDiagonalArtifact(
                            diagonalSpan, isProtected, median, field, base, left, below, (texel - width - 1) * 3, roots,
                        )) ||
                        (right >= 0 && below >= 0 && hasDiagonalArtifact(
                            diagonalSpan, isProtected, median, field, base, right, below, (texel - width + 1) * 3, roots,
                        )) ||
                        (left >= 0 && above >= 0 && hasDiagonalArtifact(
                            diagonalSpan, isProtected, median, field, base, left, above, (texel + width - 1) * 3, roots,
                        )) ||
                        (right >= 0 && above >= 0 && hasDiagonalArtifact(
                            diagonalSpan, isProtected, median, field, base, right, above, (texel + width + 1) * 3, roots,
                        ))
            if (artifact) stencil[texel] = stencil[texel] or ErrorFlag
        }
    }
}

/**
 * Whether the median interpolated at [t] between the medians [aMedian] and [bMedian] strays further
 * than the distance from either end can account for. Protected texels only report an outright
 * inversion, an interior point reading as exterior or the other way round.
 */
private fun rangeTest(
    span: Double,
    isProtected: Boolean,
    at: Double,
    bt: Double,
    xt: Double,
    aMedian: Float,
    bMedian: Float,
    xMedian: Float,
): Boolean {
    val inverted = (aMedian > 0.5f && bMedian > 0.5f && xMedian <= 0.5f) ||
            (aMedian < 0.5f && bMedian < 0.5f && xMedian >= 0.5f)
    if (!inverted && (isProtected || median(aMedian, bMedian, xMedian) == xMedian)) return false
    val aSpan = (xt - at) * span
    val bSpan = (bt - xt) * span
    val withinRange = xMedian >= aMedian - aSpan && xMedian <= aMedian + aSpan &&
            xMedian >= bMedian - bSpan && xMedian <= bMedian + bSpan
    return !withinRange
}

private fun interpolatedMedian(field: FloatArray, a: Int, b: Int, t: Double): Float = median(
    mix(field[a], field[b], t),
    mix(field[a + 1], field[b + 1], t),
    mix(field[a + 2], field[b + 2], t),
)

/**
 * Median of the bilinear interpolation at [t], from the constant, linear and quadratic terms of each
 * channel. Spelled out per channel rather than over arrays: this runs for every diagonal pair of
 * every texel of every glyph, and three arrays per call is more allocation than arithmetic.
 */
private fun interpolatedMedian(
    c0: Float, c1: Float, c2: Float,
    l0: Float, l1: Float, l2: Float,
    q0: Float, q1: Float, q2: Float,
    t: Double,
): Float = median(
    (t * (t * q0 + l0) + c0).toFloat(),
    (t * (t * q1 + l1) + c1).toFloat(),
    (t * (t * q2 + l2) + c2).toFloat(),
)

private fun hasLinearArtifact(
    span: Double,
    isProtected: Boolean,
    aMedian: Float,
    field: FloatArray,
    a: Int,
    b: Int,
): Boolean {
    val bMedian = median(field[b], field[b + 1], field[b + 2])
    if (abs(aMedian - 0.5f) < abs(bMedian - 0.5f)) return false
    return linearArtifactAt(span, isProtected, aMedian, bMedian, field, a, b, field[a + 1] - field[a], field[b + 1] - field[b]) ||
            linearArtifactAt(span, isProtected, aMedian, bMedian, field, a, b, field[a + 2] - field[a + 1], field[b + 2] - field[b + 1]) ||
            linearArtifactAt(span, isProtected, aMedian, bMedian, field, a, b, field[a] - field[a + 2], field[b] - field[b + 2])
}

/** Checks the point between two texels where a given pair of channels is equal, the median's extreme. */
private fun linearArtifactAt(
    span: Double,
    isProtected: Boolean,
    aMedian: Float,
    bMedian: Float,
    field: FloatArray,
    a: Int,
    b: Int,
    deltaA: Float,
    deltaB: Float,
): Boolean {
    val t = deltaA.toDouble() / (deltaA - deltaB)
    if (!(t > ArtifactTEpsilon && t < 1.0 - ArtifactTEpsilon)) return false
    val xMedian = interpolatedMedian(field, a, b, t)
    return rangeTest(span, isProtected, 0.0, 1.0, t, aMedian, bMedian, xMedian)
}

/**
 * The bilinear terms are carried as loose scalars rather than three arrays, because this is called
 * for four diagonal neighbors of every texel of every glyph, the arrays would have been the bulk
 * of the bake's allocation.
 */
private fun hasDiagonalArtifact(
    span: Double,
    isProtected: Boolean,
    aMedian: Float,
    field: FloatArray,
    a: Int,
    b: Int,
    c: Int,
    d: Int,
    roots: DoubleArray,
): Boolean {
    val dMedian = median(field[d], field[d + 1], field[d + 2])
    if (abs(aMedian - 0.5f) < abs(dMedian - 0.5f)) return false
    val abc0 = field[a] - field[b] - field[c]
    val abc1 = field[a + 1] - field[b + 1] - field[c + 1]
    val abc2 = field[a + 2] - field[b + 2] - field[c + 2]
    val c0 = field[a]
    val c1 = field[a + 1]
    val c2 = field[a + 2]
    val l0 = -c0 - abc0
    val l1 = -c1 - abc1
    val l2 = -c2 - abc2
    val q0 = field[d] + abc0
    val q1 = field[d + 1] + abc1
    val q2 = field[d + 2] + abc2
    val e0 = -0.5 * l0 / q0
    val e1 = -0.5 * l1 / q1
    val e2 = -0.5 * l2 / q2
    return diagonalArtifactAt(
        span, isProtected, aMedian, dMedian, c0, c1, c2, l0, l1, l2, q0, q1, q2,
        field[a + 1] - field[a], field[b + 1] - field[b] + field[c + 1] - field[c], field[d + 1] - field[d],
        e0, e1, roots,
    ) || diagonalArtifactAt(
        span, isProtected, aMedian, dMedian, c0, c1, c2, l0, l1, l2, q0, q1, q2,
        field[a + 2] - field[a + 1], field[b + 2] - field[b + 1] + field[c + 2] - field[c + 1],
        field[d + 2] - field[d + 1],
        e1, e2, roots,
    ) || diagonalArtifactAt(
        span, isProtected, aMedian, dMedian, c0, c1, c2, l0, l1, l2, q0, q1, q2,
        field[a] - field[a + 2], field[b] - field[b + 2] + field[c] - field[c + 2], field[d] - field[d + 2],
        e2, e0, roots,
    )
}

private fun diagonalArtifactAt(
    span: Double,
    isProtected: Boolean,
    aMedian: Float,
    dMedian: Float,
    c0: Float, c1: Float, c2: Float,
    l0: Float, l1: Float, l2: Float,
    q0: Float, q1: Float, q2: Float,
    deltaA: Float,
    deltaBC: Float,
    deltaD: Float,
    extreme0: Double,
    extreme1: Double,
    roots: DoubleArray,
): Boolean {
    val solutions = solveQuadratic(
        roots,
        (deltaD - deltaBC + deltaA).toDouble(),
        (deltaBC - deltaA - deltaA).toDouble(),
        deltaA.toDouble(),
    )
    for (index in 0 until solutions) {
        val t = roots[index]
        if (!(t > ArtifactTEpsilon && t < 1.0 - ArtifactTEpsilon)) continue
        val xMedian = interpolatedMedian(c0, c1, c2, l0, l1, l2, q0, q1, q2, t)
        val artifact = rangeTest(span, isProtected, 0.0, 1.0, t, aMedian, dMedian, xMedian) ||
                extremeRangeTest(span, isProtected, t, aMedian, dMedian, xMedian, c0, c1, c2, l0, l1, l2, q0, q1, q2, extreme0) ||
                extremeRangeTest(span, isProtected, t, aMedian, dMedian, xMedian, c0, c1, c2, l0, l1, l2, q0, q1, q2, extreme1)
        if (artifact) return true
    }
    return false
}

private fun extremeRangeTest(
    span: Double,
    isProtected: Boolean,
    t: Double,
    aMedian: Float,
    dMedian: Float,
    xMedian: Float,
    c0: Float, c1: Float, c2: Float,
    l0: Float, l1: Float, l2: Float,
    q0: Float, q1: Float, q2: Float,
    extreme: Double,
): Boolean {
    if (!(extreme > 0.0 && extreme < 1.0)) return false
    var startT = 0.0
    var endT = 1.0
    var startMedian = aMedian
    var endMedian = dMedian
    val extremeMedian = interpolatedMedian(c0, c1, c2, l0, l1, l2, q0, q1, q2, extreme)
    if (extreme > t) {
        endT = extreme
        endMedian = extremeMedian
    } else {
        startT = extreme
        startMedian = extremeMedian
    }
    return rangeTest(span, isProtected, startT, endT, t, startMedian, endMedian, xMedian)
}

/** Flattens every flagged texel to its own median, which is the value the shader would have read. */
private fun applyErrors(stencil: IntArray, field: FloatArray, width: Int, height: Int) {
    for (texel in 0 until width * height) {
        if (stencil[texel] and ErrorFlag == 0) continue
        val base = texel * 3
        val median = median(field[base], field[base + 1], field[base + 2])
        field[base] = median
        field[base + 1] = median
        field[base + 2] = median
    }
}

private fun solveQuadratic(roots: DoubleArray, a: Double, b: Double, c: Double): Int {
    if (a == 0.0 || abs(b) > 1e12 * abs(a)) {
        if (b == 0.0) return 0
        roots[0] = -c / b
        return 1
    }
    val discriminant = b * b - 4 * a * c
    return when {
        discriminant > 0.0 -> {
            val root = sqrt(discriminant)
            roots[0] = (-b + root) / (2 * a)
            roots[1] = (-b - root) / (2 * a)
            2
        }

        discriminant == 0.0 -> {
            roots[0] = -b / (2 * a)
            1
        }

        else -> 0
    }
}
