package ru.hollowhorizon.hollowengine.client.ui.style

import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.shape.Shape
import ru.hollowhorizon.hollowengine.client.ui.shape.SvgPathShape
import ru.hollowhorizon.hollowengine.client.ui.shape.svgResource

internal fun parseAlign(value: String): UiAlign = when (value.lowercase()) {
    "auto" -> UiAlign.AUTO
    "start", "flex-start" -> UiAlign.START
    "center" -> UiAlign.CENTER
    "end", "flex-end" -> UiAlign.END
    "stretch" -> UiAlign.STRETCH
    "space-between" -> UiAlign.SPACE_BETWEEN
    "space-around" -> UiAlign.SPACE_AROUND
    "space-evenly" -> UiAlign.SPACE_EVENLY
    "justify" -> UiAlign.JUSTIFY
    else -> throw IllegalArgumentException("Unknown align '$value'")
}

internal fun applySelfAlignment(style: UiStylePatch, value: String) {
    val parts = splitWhitespace(value)
    style.alignHorizontal = parseAlign(parts.first())
    style.alignVertical = parseAlign(parts.getOrElse(1) { parts.first() })
}

internal fun applyChildAlignment(style: UiStylePatch, value: String) {
    val parts = splitWhitespace(value)
    style.alignItemsHorizontal = parseAlign(parts.first())
    style.alignItemsVertical = parseAlign(parts.getOrElse(1) { parts.first() })
}

internal fun parseImageFit(value: String): UiImageFit = when (splitWhitespace(value).first().lowercase()) {
    "stretch", "strench" -> UiImageFit.STRETCH
    "contain", "fit" -> UiImageFit.CONTAIN
    "cover", "zoom" -> UiImageFit.COVER
    "none" -> UiImageFit.NONE
    "9-slice", "nine-slice" -> UiImageFit.NINE_SLICE
    "3-slice-vertical", "three-slice-vertical" -> UiImageFit.THREE_SLICE_VERTICAL
    "3-slice-horizontal", "three-slice-horizontal" -> UiImageFit.THREE_SLICE_HORIZONTAL
    else -> throw IllegalArgumentException("Unknown image fit '$value'")
}

/** Keywords accepted by `image-fit` and the widget part fits; also completed by the IDE. */
internal val UiImageFitKeywords = listOf(
    "stretch",
    "contain",
    "cover",
    "none",
    "9-slice",
    "3-slice-vertical",
    "3-slice-horizontal",
)

/** `none` or `<x> <y> <width> <height>` in the source texture's own coordinates. */
internal fun parseImageUv(value: String): UiImageUv {
    val trimmed = value.trim()
    if (trimmed.equals("none", ignoreCase = true) || trimmed.equals("full", ignoreCase = true)) return UiImageUv.Full
    val parts = splitWhitespace(trimmed)
    require(parts.size == 4) { "Expected image-uv x y width height, got '$value'" }
    return UiImageUv(
        x = parseLength(parts[0], allowAuto = false),
        y = parseLength(parts[1], allowAuto = false),
        width = parseLength(parts[2], allowAuto = false),
        height = parseLength(parts[3], allowAuto = false),
    )
}

internal fun parseImageFitSlice(value: String): UiInsets? {
    val parts = splitWhitespace(value)
    return parts.drop(1).takeIf { it.isNotEmpty() }?.joinToString(" ")?.let { parseInsets(it, allowAuto = false) }
}

internal fun UiScrollbarStyle.patchTrack(patch: (UiScrollbarPartStyle) -> UiScrollbarPartStyle): UiScrollbarStyle =
    copy(track = patch(track))

internal fun UiScrollbarStyle.patchThumb(patch: (UiScrollbarPartStyle) -> UiScrollbarPartStyle): UiScrollbarStyle =
    copy(thumb = patch(thumb))

internal fun parseTextWrap(value: String): Boolean = when (value.lowercase()) {
    "wrap", "true", "yes", "1", "enabled" -> true
    "nowrap", "no-wrap", "false", "no", "0", "disabled" -> false
    else -> throw IllegalArgumentException("Unknown text wrap '$value'")
}

internal fun parseTextOverflow(value: String): UiTextOverflow = when (value.lowercase()) {
    "show", "visible", "none" -> UiTextOverflow.SHOW
    "hidden", "clip" -> UiTextOverflow.HIDDEN
    "dots", "ellipsis" -> UiTextOverflow.DOTS
    else -> throw IllegalArgumentException("Unknown text-overflow '$value'")
}

internal fun parseBoxDecorationBreak(value: String): UiBoxDecorationBreak = when (value.trim().lowercase()) {
    "slice" -> UiBoxDecorationBreak.SLICE
    "clone" -> UiBoxDecorationBreak.CLONE
    else -> throw IllegalArgumentException("Unknown box-decoration-break '$value'")
}

internal fun parseWhitespace(value: String): UiWhitespace = when (value.trim().lowercase()) {
    "normal", "collapse" -> UiWhitespace.COLLAPSE
    "pre", "preserve", "pre-wrap" -> UiWhitespace.PRESERVE
    else -> throw IllegalArgumentException("Unknown white-space '$value'")
}

internal fun parseTextAlign(value: String): UiTextAlign = when (value.lowercase()) {
    "left", "start" -> UiTextAlign.LEFT
    "right", "end" -> UiTextAlign.RIGHT
    "center" -> UiTextAlign.CENTER
    "justify" -> UiTextAlign.JUSTIFY
    else -> throw IllegalArgumentException("Unknown text-align '$value'")
}

internal fun parseCursor(value: String): UiCursorShape = when (val cleaned = value.trim().lowercase()) {
    "pointer" -> UiCursorShape.HAND
    "ew-resize", "col-resize" -> UiCursorShape.RESIZE_HORIZONTAL
    "ns-resize", "row-resize" -> UiCursorShape.RESIZE_VERTICAL
    else -> parseEnum<UiCursorShape>(cleaned, "cursor")
}

/** Kebab-cased enum lookup, so an engine enum never has to be mirrored as a `when`. */
internal inline fun <reified T : Enum<T>> parseEnum(value: String, what: String): T {
    val cleaned = value.trim().replace('-', '_')
    return enumValues<T>().firstOrNull { it.name.equals(cleaned, ignoreCase = true) }
        ?: throw IllegalArgumentException("Unknown $what '$value'")
}

/** Kebab-cased names of an engine enum, used as value completions. */
internal inline fun <reified T : Enum<T>> enumKeywords(): List<String> =
    enumValues<T>().map { it.name.lowercase().replace('_', '-') }

internal fun parseAspectRatio(value: String): Float {
    val parts = value.split('/').map { it.trim() }.filter { it.isNotBlank() }
    return when (parts.size) {
        1 -> parts.single().toFloat()
        2 -> parts[0].toFloat() / parts[1].toFloat()
        else -> throw IllegalArgumentException("Unknown aspect ratio '$value'")
    }.coerceAtLeast(0.0001f)
}

internal fun parseSize(value: String): UiSize {
    val parts = splitWhitespace(value)
    val width = parseLength(parts.first())
    val height = parseLength(parts.getOrElse(1) { parts.first() })
    return UiSize(width, height)
}

internal fun parseInsets(value: String, allowAuto: Boolean): UiInsets {
    val parts = splitWhitespace(value)
    val top = parseLength(parts[0], allowAuto)
    val right = parseLength(parts.getOrElse(1) { parts[0] }, allowAuto)
    val bottom = parseLength(parts.getOrElse(2) { parts[0] }, allowAuto)
    val left = parseLength(parts.getOrElse(3) { parts.getOrElse(1) { parts[0] } }, allowAuto)
    return UiInsets(left, top, right, bottom)
}

internal fun parseLength(value: String, allowAuto: Boolean = true): UiLength {
    val cleaned = value.trim().lowercase()
    if (cleaned == "fit" && allowAuto) return UiLength.Fit
    if (cleaned == "auto" && allowAuto) return UiLength.Auto
    if (cleaned == "fill") return UiLength.Fill
    if (cleaned.endsWith("px")) return cleaned.dropLast(2).toFloat().px
    if (cleaned.endsWith("%")) return UiLength.Percent(cleaned.dropLast(1).toFloat() / 100f)
    return cleaned.toFloat().px
}

internal fun parsePaint(value: String): UiPaint {
    if (value.trim().equals("none", ignoreCase = true)) return UiPaint.None
    parseBoundFunction(value, "image")?.let { return UiPaint.Image(it) }
    parseBoundFunction(value, "url")?.let { return UiPaint.Image(it) }
    parseBoundFunction(value, "shader")?.let { return UiPaint.Shader(it) }
    parseLinearGradient(value)?.let { return it }
    parseRadialGradient(value)?.let { return it }
    if (looksLikeImageSource(value)) return UiPaint.Image(parseImageSource(value))
    return UiPaint.Color(parseColor(value))
}

internal fun parseImageSource(value: String): String {
    return parseBoundFunction(value, "image")
        ?: parseBoundFunction(value, "url")
        ?: unquote(value)
}

internal fun applyClip(style: UiStylePatch, value: String) {
    val cleaned = value.trim()
    if (cleaned.startsWith("path(") || cleaned.startsWith("svg-path(") || cleaned.startsWith("svg(")) {
        style.clip = true
        style.clipShape = parseShape(cleaned)
        return
    }
    style.clip = parseBoolean(cleaned)
    if (style.clip == false) style.clipShape = null
}

internal fun parseShape(value: String): Shape {
    val cleaned = value.trim()
    val functionName = when {
        cleaned.startsWith("path(") -> "path"
        cleaned.startsWith("svg-path(") -> "svg-path"
        cleaned.startsWith("svg(") -> "svg"
        else -> throw IllegalArgumentException("Expected path(...) or svg(...), got '$value'")
    }
    val args = functionArgs(cleaned, functionName)
    require(args.isNotEmpty()) { "$functionName requires SVG path data or resource location" }
    if (functionName == "svg") return svgResource(unquote(args.first()))
    return SvgPathShape(unquote(args.first()), parseShapeViewBox(args.drop(1)))
}

private fun parseShapeViewBox(args: List<String>): UiRect? {
    if (args.isEmpty()) return null
    val numbers = splitWhitespace(args.joinToString(" ")).map(::parseScalar)
    return when (numbers.size) {
        2 -> UiRect(0f, 0f, numbers[0], numbers[1])
        4 -> UiRect(numbers[0], numbers[1], numbers[2], numbers[3])
        else -> throw IllegalArgumentException("Shape viewBox expects width height or x y width height")
    }
}

private fun parseLinearGradient(value: String): UiPaint.LinearGradient? {
    val cleaned = value.trim()
    if (!cleaned.startsWith("linear-gradient(")) return null
    val args = functionArgs(cleaned, "linear-gradient")
    val first = args.firstOrNull()?.trim().orEmpty()
    val angle = parseGradientAngle(first)
    val colorArgs = if (angle != null) args.drop(1) else args
    require(colorArgs.size >= 2) { "linear-gradient requires at least two colors" }
    return UiPaint.LinearGradient(angle ?: 180f, parseGradientStops(colorArgs))
}

private fun parseRadialGradient(value: String): UiPaint.RadialGradient? {
    val cleaned = value.trim()
    if (!cleaned.startsWith("radial-gradient(")) return null
    val args = functionArgs(cleaned, "radial-gradient")
    val first = args.firstOrNull()?.trim().orEmpty()
    val descriptor = first.takeIf { it.isNotEmpty() && !looksLikeColor(it) }
    val colorArgs = if (descriptor == null) args else args.drop(1)
    require(colorArgs.size >= 2) { "radial-gradient requires at least two colors" }
    val gradient = parseRadialGradientDescriptor(descriptor).copy(stops = parseGradientStops(colorArgs))
    return UiPaint.RadialGradient(gradient)
}

private fun parseRadialGradientDescriptor(value: String?): UiRadialGradient {
    if (value == null) return UiRadialGradient(stops = emptyList())
    val tokens = splitTopLevelWhitespace(value)
    val atIndex = tokens.indexOfFirst { it.equals("at", ignoreCase = true) }
    val radius = tokens.take(atIndex.takeIf { it >= 0 } ?: tokens.size)
        .firstOrNull { it.endsWith("%") || it.endsWith("px") || it.toFloatOrNull() != null }
        ?.let { parseLength(it, allowAuto = false) }
        ?: 50.percent
    val center = if (atIndex >= 0) tokens.drop(atIndex + 1) else emptyList()
    return UiRadialGradient(
        centerX = center.getOrNull(0)?.let { parseLength(it, allowAuto = false) } ?: 50.percent,
        centerY = center.getOrNull(1)?.let { parseLength(it, allowAuto = false) } ?: 50.percent,
        radius = radius,
        stops = emptyList(),
    )
}

private fun parseGradientStops(args: List<String>): List<UiGradientStop> {
    return args.mapIndexed { index, entry ->
        val parts = splitTopLevelWhitespace(entry)
        val color = parseColor(parts.first())
        val offset = parts.getOrNull(1)?.let(::parseStopOffset)
            ?: if (args.size == 1) 0f else index.toFloat() / (args.size - 1).toFloat()
        UiGradientStop(offset.coerceIn(0f, 1f), color)
    }.sortedBy { it.offset }
}

fun parseColor(value: String): UiColor {
    val cleaned = value.trim()
    if (cleaned.startsWith("alpha(")) {
        val args = functionArgs(cleaned, "alpha")
        require(args.size == 2) { "Expected alpha(color, opacity), got '$value'" }
        val base = parseColor(args[0])
        val opacity = args[1].trim().let { if (it.endsWith("%")) it.dropLast(1).toFloat() / 100f else it.toFloat() }
        return UiColor(base.red, base.green, base.blue, base.alpha * opacity)
    }
    if (cleaned.startsWith("rgba(")) {
        val args = functionArgs(cleaned, "rgba").map { it.trim().toFloat() }
        return UiColor(args[0] / 255f, args[1] / 255f, args[2] / 255f, args.getOrElse(3) { 1f })
    }
    if (cleaned.startsWith("rgb(")) {
        val args = functionArgs(cleaned, "rgb").map { it.trim().toFloat() }
        return UiColor(args[0] / 255f, args[1] / 255f, args[2] / 255f)
    }
    if (cleaned.startsWith("#")) {
        val hex = cleaned.removePrefix("#")
        require(hex.length >= 6) { "Expected #RRGGBB or #RRGGBBAA, got '$value'" }
        val red = hex.substring(0, 2).toInt(16) / 255f
        val green = hex.substring(2, 4).toInt(16) / 255f
        val blue = hex.substring(4, 6).toInt(16) / 255f
        val alpha = if (hex.length >= 8) hex.substring(6, 8).toInt(16) / 255f else 1f
        return UiColor(red, green, blue, alpha)
    }
    return when (cleaned.lowercase()) {
        "transparent" -> UiColor.Transparent
        "white" -> UiColor.White
        "black" -> UiColor.Black
        else -> throw IllegalArgumentException("Unknown color '$value'")
    }
}

internal fun parseBorder(value: String, previous: UiBorder): UiBorder {
    val parts = splitTopLevelWhitespace(value)
    val width = parseLength(parts.first())
    val paint = parts.drop(1).joinToString(" ").takeIf { it.isNotBlank() }?.let(::parsePaint) ?: previous.paint
    return previous.copy(width = UiInsets.all(width), paint = paint)
}

internal fun parseShadows(value: String): List<UiShadow> {
    if (value.equals("none", ignoreCase = true)) return emptyList()
    return splitTopLevel(value, ',').map { entry ->
        val parts = splitTopLevelWhitespace(entry)
        val inset = parts.firstOrNull()?.equals("inset", ignoreCase = true) == true
        val values = if (inset) parts.drop(1) else parts
        require(values.size >= 3) { "Expected shadow x y blur [spread] color, got '$entry'" }
        val spreadIndex = values.indexOfFirst { looksLikeColor(it) }.let { colorIndex ->
            if (colorIndex < 0) values.lastIndex else colorIndex
        }
        val color = values.drop(spreadIndex).joinToString(" ").takeIf { it.isNotBlank() }?.let(::parseColor)
            ?: throw IllegalArgumentException("Expected shadow color, got '$entry'")
        UiShadow(
            offset = UiVec3(parseScalar(values[0]), parseScalar(values[1]), 0f),
            blur = parseScalar(values.getOrElse(2) { "0px" }).coerceAtLeast(0f),
            spread = values.getOrNull(3)?.takeUnless(::looksLikeColor)?.let(::parseScalar) ?: 0f,
            color = color,
            inset = inset,
        )
    }
}

internal fun parseFilterChain(value: String): UiFilterChain {
    if (value.equals("none", ignoreCase = true)) return UiFilterChain.Empty
    val effects = parseValueFunctions(value).map { (name, args) ->
        when (name) {
            "grayscale" -> UiFilterEffect.Grayscale(parseFilterAmount(args))
            "blur" -> UiFilterEffect.Blur(parseScalar(args).coerceAtLeast(0f))
            "shader" -> UiFilterEffect.Shader(unquote(args))
            "linear-gradient" -> parseLinearMask(args)
            else -> UiFilterEffect.Shader(name)
        }
    }
    return UiFilterChain(effects)
}

/** `mask: none` clears it; anything else is a gradient the node is seen through. */
internal fun parseMask(value: String): UiFilterEffect.LinearMask? {
    if (value.isBlank() || value.equals("none", ignoreCase = true)) return null
    val (name, args) = parseValueFunctions(value).firstOrNull()
        ?: throw IllegalArgumentException("Expected a gradient, got '$value'")
    require(name == "linear-gradient") { "Only linear-gradient() masks are supported, got '$name'" }
    return parseLinearMask(args)
}

/**
 * `linear-gradient(to bottom, transparent, white 20%, white 80%, transparent)`, the CSS spelling,
 * with the same defaults: no angle means top-to-bottom, and stops without a position are spread
 * evenly between the ones that have one.
 *
 * Only the alpha of a stop is used, so `transparent` hides and any opaque color shows.
 */
private fun parseLinearMask(args: String): UiFilterEffect.LinearMask {
    val parts = splitTopLevel(args, ',').map { it.trim() }.filter { it.isNotEmpty() }
    require(parts.isNotEmpty()) { "linear-gradient() needs at least one color stop" }

    val angle = parseGradientAngle(parts.first())
    val stopParts = if (angle != null) parts.drop(1) else parts
    require(stopParts.isNotEmpty()) { "linear-gradient() needs at least one color stop" }

    val positions = arrayOfNulls<Float>(stopParts.size)
    val alphas = FloatArray(stopParts.size)
    stopParts.forEachIndexed { index, part ->
        val pieces = splitTopLevelWhitespace(part)
        val position = pieces.lastOrNull()?.takeIf { it.endsWith("%") || it.toFloatOrNull() != null }
        val colorText = if (position != null && pieces.size > 1) pieces.dropLast(1).joinToString(" ") else part
        alphas[index] = parseColor(colorText).alpha
        positions[index] = position?.let { text ->
            if (text.endsWith("%")) text.dropLast(1).trim().toFloat() / 100f else text.toFloat()
        }
    }
    positions[0] = positions[0] ?: 0f
    positions[positions.lastIndex] = positions[positions.lastIndex] ?: 1f
    spreadMissingStops(positions)

    return UiFilterEffect.LinearMask(
        angle = angle ?: 180f,
        stops = positions.mapIndexed { index, position -> MaskStop(position ?: 0f, alphas[index]) },
    )
}

/** `to bottom`, `45deg`, or null when the first part is already a color stop. */
private fun parseGradientAngle(part: String): Float? {
    val text = part.trim().lowercase()
    if (text.startsWith("to ")) {
        return when (text.removePrefix("to ").trim()) {
            "top" -> 0f
            "right" -> 90f
            "bottom" -> 180f
            "left" -> 270f
            else -> throw IllegalArgumentException("Unknown gradient direction '$part'")
        }
    }
    if (text.endsWith("deg")) return text.removeSuffix("deg").trim().toFloat()
    return text.toFloatOrNull()
}

private fun spreadMissingStops(positions: Array<Float?>) {
    var index = 0
    while (index < positions.size) {
        if (positions[index] != null) {
            index++
            continue
        }
        val gapStart = index
        while (index < positions.size && positions[index] == null) index++
        val before = positions[gapStart - 1] ?: 0f
        val after = positions.getOrNull(index) ?: 1f
        val step = (after - before) / (index - gapStart + 1)
        for (offset in gapStart until index) positions[offset] = before + step * (offset - gapStart + 1)
    }
}

internal fun parseBackfaceVisibility(value: String): UiBackfaceVisibility = when (value.lowercase()) {
    "visible" -> UiBackfaceVisibility.VISIBLE
    "hidden" -> UiBackfaceVisibility.HIDDEN
    else -> throw IllegalArgumentException("Unknown backface-visibility '$value'")
}

internal fun parseVec3(value: String): UiVec3 {
    val parts = splitWhitespace(value).map(::parseScalar)
    return UiVec3(parts.getOrElse(0) { 0f }, parts.getOrElse(1) { 0f }, parts.getOrElse(2) { 0f })
}

internal fun parsePosition(value: String): UiPosition {
    val parts = splitWhitespace(value)
    return UiPosition(
        x = parseLength(parts.getOrElse(0) { "0px" }),
        y = parseLength(parts.getOrElse(1) { "0px" }),
        z = parts.getOrNull(2)?.let(::parseScalar) ?: 0f,
    )
}

internal fun parsePivot(value: String): UiTransformPivot {
    val cleaned = value.trim().lowercase()
    namedPivot(cleaned)?.let { return it }
    val parts = splitWhitespace(value)
    if (parts.size == 1) namedPivot(parts[0].lowercase())?.let { return it }
    return UiTransformPivot(
        x = parsePivotLength(parts.getOrElse(0) { "50%" }),
        y = parsePivotLength(parts.getOrElse(1) { "50%" }),
        z = parts.getOrNull(2)?.let(::parsePivotLength) ?: 0.px,
    )
}

private fun namedPivot(value: String): UiTransformPivot? = when (value) {
    "top-left" -> UiTransformPivot.TopLeft
    "top-center" -> UiTransformPivot.TopCenter
    "top-right" -> UiTransformPivot.TopRight
    "center-left" -> UiTransformPivot.CenterLeft
    "center" -> UiTransformPivot.Center
    "center-right" -> UiTransformPivot.CenterRight
    "bottom-left" -> UiTransformPivot.BottomLeft
    "bottom-center" -> UiTransformPivot.BottomCenter
    "bottom-right" -> UiTransformPivot.BottomRight
    else -> null
}

/** Names accepted by `pivot`/`transform-origin`; also completed by the IDE. */
internal val UiPivotKeywords = listOf(
    "center",
    "top-left",
    "top-center",
    "top-right",
    "center-left",
    "center-right",
    "bottom-left",
    "bottom-center",
    "bottom-right",
)

private fun parsePivotLength(value: String): UiLength {
    val cleaned = value.trim().lowercase()
    if (cleaned.endsWith("%")) return UiLength.Percent(cleaned.dropLast(1).toFloat() / 100f)
    if (cleaned.endsWith("px")) return cleaned.dropLast(2).toFloat().px
    return cleaned.toFloat().px
}

internal fun parseScale(value: String): UiVec3 {
    val parts = splitWhitespace(value).map(::parseScalar)
    val x = parts.getOrElse(0) { 1f }
    return UiVec3(x, parts.getOrElse(1) { x }, parts.getOrElse(2) { 1f })
}

internal fun parseTransform(value: String, base: UiTransform): UiTransform {
    if (value.equals("none", ignoreCase = true)) return UiTransform()
    var transform = base
    for ((name, args) in parseValueFunctions(value)) {
        transform = when (name) {
            "translate" -> {
                val parts = splitTransformArgs(args).map(::parseScalar)
                transform.copy(
                    translate = UiVec3(
                        parts.getOrElse(0) { 0f },
                        parts.getOrElse(1) { 0f },
                        parts.getOrElse(2) { 0f },
                    ),
                )
            }

            "translatex" -> transform.copy(translate = transform.translate.copy(x = parseScalar(args)))
            "translatey" -> transform.copy(translate = transform.translate.copy(y = parseScalar(args)))
            "translatez" -> transform.copy(translate = transform.translate.copy(z = parseScalar(args)))
            "scale" -> {
                val parts = splitTransformArgs(args).map(::parseScalar)
                val x = parts.getOrElse(0) { 1f }
                transform.copy(scale = UiVec3(x, parts.getOrElse(1) { x }, parts.getOrElse(2) { 1f }))
            }

            "scalex" -> transform.copy(scale = transform.scale.copy(x = parseScalar(args)))
            "scaley" -> transform.copy(scale = transform.scale.copy(y = parseScalar(args)))
            "scalez" -> transform.copy(scale = transform.scale.copy(z = parseScalar(args)))
            "rotate" -> transform.copy(rotate = transform.rotate.copy(z = parseScalar(args)))
            "rotatex" -> transform.copy(rotate = transform.rotate.copy(x = parseScalar(args)))
            "rotatey" -> transform.copy(rotate = transform.rotate.copy(y = parseScalar(args)))
            "rotatez" -> transform.copy(rotate = transform.rotate.copy(z = parseScalar(args)))
            "perspective" -> transform.copy(perspective = parseScalar(args))
            else -> transform
        }
    }
    return transform
}

/** Transform functions accepted by `transform`; also completed by the IDE. */
internal val UiTransformFunctions = listOf(
    "translate(0, 0)",
    "translateX(0)",
    "translateY(0)",
    "translateZ(0)",
    "scale(1)",
    "scaleX(1)",
    "scaleY(1)",
    "rotate(0deg)",
    "rotateX(0deg)",
    "rotateY(0deg)",
    "rotateZ(0deg)",
    "perspective(300px)",
    "none",
)

private fun splitTransformArgs(args: String): List<String> {
    return if (args.contains(',')) splitTopLevel(args, ',') else splitWhitespace(args)
}

/** Parses a `name(args) name(args)` chain into its parts, in source order. */
internal fun parseValueFunctions(value: String): List<Pair<String, String>> {
    val result = mutableListOf<Pair<String, String>>()
    var index = 0
    while (index < value.length) {
        while (index < value.length && value[index].isWhitespace()) index++
        val nameStart = index
        while (index < value.length && (value[index].isLetterOrDigit() || value[index] == '-')) index++
        if (nameStart == index) break
        val name = value.substring(nameStart, index).lowercase()
        require(value.getOrNull(index) == '(') { "Expected a function call after '$name'" }
        val argsStart = index + 1
        val close = findFunctionClose(value, index)
        result += name to value.substring(argsStart, close).trim()
        index = close + 1
    }
    return result
}

private fun findFunctionClose(value: String, open: Int): Int {
    var depth = 0
    var inString = false
    var quote = '\u0000'
    for (index in open until value.length) {
        val char = value[index]
        if (inString) {
            if (char == quote && value.getOrNull(index - 1) != '\\') inString = false
        } else {
            when (char) {
                '\'', '"' -> {
                    inString = true
                    quote = char
                }

                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
    }
    throw IllegalArgumentException("Unclosed function call in '$value'")
}

internal fun parseScalar(value: String): Float = value.trim().removeSuffix("px").removeSuffix("deg").toFloat()

/** `12px` / `12` are absolute; `85%` and `0.85em` follow the size of the text around them. */
internal fun parseFontSize(value: String): UiFontSize {
    val cleaned = value.trim().lowercase()
    if (cleaned.endsWith("%")) return UiFontSize.scaled(cleaned.dropLast(1).toFloat() / 100f)
    if (cleaned.endsWith("em")) return UiFontSize.scaled(cleaned.dropLast(2).toFloat())
    return UiFontSize.of(parseScalar(cleaned))
}

private fun parseStopOffset(value: String): Float {
    val cleaned = value.trim()
    if (cleaned.endsWith("%")) return cleaned.dropLast(1).toFloat() / 100f
    return parseScalar(cleaned).coerceIn(0f, 1f)
}

private fun parseFilterAmount(value: String): Float {
    val cleaned = value.trim()
    if (cleaned.endsWith("%")) return cleaned.dropLast(1).toFloat() / 100f
    return cleaned.toFloat()
}

internal fun looksLikeColor(value: String): Boolean {
    val cleaned = value.trim().lowercase()
    return cleaned.startsWith("rgba(") ||
            cleaned.startsWith("rgb(") ||
            cleaned.startsWith("alpha(") ||
            cleaned.startsWith("#") ||
            cleaned == "transparent" ||
            cleaned == "white" ||
            cleaned == "black"
}

private fun looksLikeImageSource(value: String): Boolean {
    val cleaned = unquote(value).lowercase()
    return ":" in cleaned || cleaned.endsWith(".png") || cleaned.endsWith(".jpg") || cleaned.endsWith(".jpeg")
}

internal fun parseBoundFunction(value: String, name: String): String? {
    if (!value.trim().startsWith("$name(")) return null
    return unquote(functionArgs(value.trim(), name).joinToString(",").trim())
}

internal fun parseBoolean(value: String): Boolean = when (value.lowercase()) {
    "true", "yes", "1", "enabled" -> true
    "false", "no", "0", "disabled" -> false
    else -> throw IllegalArgumentException("Expected boolean, got '$value'")
}
