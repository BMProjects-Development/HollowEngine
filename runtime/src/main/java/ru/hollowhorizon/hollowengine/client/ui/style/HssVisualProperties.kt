package ru.hollowhorizon.hollowengine.client.ui.style

import ru.hollowhorizon.hollowengine.client.ui.UiBorder
import ru.hollowhorizon.hollowengine.client.ui.UiBorderDash
import ru.hollowhorizon.hollowengine.client.ui.UiInsets
import ru.hollowhorizon.hollowengine.client.ui.UiLength
import ru.hollowhorizon.hollowengine.client.ui.UiTransform
import ru.hollowhorizon.hollowengine.client.ui.UiVec3

private fun paintSlot(name: String = "paint") = slot(name, HssValueKind.PAINT)

private fun colorSlot(name: String = "color") = slot(name, HssValueKind.COLOR)

/** Fills, borders, shadows, transforms and other purely visual properties. */
internal fun visualHssProperties(): List<HssProperty> = hssProperties {
    property(
        "background",
        summary = "Fill drawn behind the node: color, image, shader or gradient.",
        syntax = syntax(paintSlot()),
        examples = listOf("#1E2128", "transparent", "image(\"\")", "linear-gradient(to left, #000000, #FFFFFF)"),
    ) { set(UiProps.Background, parsePaint(value)) }

    property(
        "background-image",
        summary = "Fill drawn behind the node, always read as an image source.",
        syntax = syntax(slot("source", HssValueKind.RESOURCE)),
        examples = listOf("image(\"hollowengine:textures/gui/panel.png\")", "url(\"\")"),
    ) { set(UiProps.Background, UiPaint.Image(parseImageSource(value))) }

    property(
        "foreground", "color",
        summary = "Text and icon color; inherited by children.",
        syntax = syntax(colorSlot()),
        examples = listOf("#FFFFFF", "#cbd3df", "rgba(255, 255, 255, 0.7)"),
    ) { style { it.foreground = parseColor(value) } }

    property(
        "image",
        summary = "Texture drawn inside the node.",
        syntax = syntax(slot("source", HssValueKind.RESOURCE)),
        examples = listOf("image(\"hollowengine:textures/gui/icon.png\")"),
    ) { style { it.image = parseBoundFunction(value, "image") ?: unquote(value) } }

    property(
        "shader",
        summary = "Shader drawn inside the node.",
        syntax = syntax(slot("source", HssValueKind.RESOURCE)),
        examples = listOf("shader(\"hollowengine:ui/wave\")"),
    ) { style { it.shader = parseBoundFunction(value, "shader") ?: unquote(value) } }

    property(
        "border",
        summary = "Border width and fill; the radius stays whatever `border-radius` set.",
        syntax = syntax(sizeSlot("width", auto = false), paintSlot("color")),
        examples = listOf(
            "1px #5F6677",
            "2px rgba(255, 255, 255, 0.5)",
            "1px linear-gradient(210deg, #9BF763, #70A832)",
        ),
    ) {
        style {
            val parsed = parseBorder(value, UiBorder())
            it.borderWidth = parsed.width
            it.borderPaint = parsed.paint
        }
    }

    property(
        "border-color",
        summary = "Border fill: a color, or a gradient stroked around the node.",
        syntax = syntax(paintSlot("color")),
        examples = listOf("#5F6677", "linear-gradient(210deg, #9BF763, #70A832)"),
    ) { style { it.borderPaint = parsePaint(value) } }

    property(
        "border-width",
        summary = "Border width on every edge.",
        syntax = syntax(sizeSlot("width", auto = false)),
    ) { style { it.borderWidth = UiInsets.all(parseLength(value, allowAuto = false)) } }

    borderEdge("border-top", "top") { insets, width -> insets.copy(top = width) }
    borderEdge("border-right", "right") { insets, width -> insets.copy(right = width) }
    borderEdge("border-bottom", "bottom") { insets, width -> insets.copy(bottom = width) }
    borderEdge("border-left", "left") { insets, width -> insets.copy(left = width) }

    property(
        "border-dash",
        summary = "Dashes the border: dash length, then the gap after it. A zero length draws it solid.",
        syntax = syntax(
            slot("dash", HssValueKind.PIXELS),
            slot("gap", HssValueKind.PIXELS, optional = true),
        ),
        examples = listOf("4px 3px", "6px", "0px"),
    ) {
        val parts = value.trim().split(Regex("\\s+"))
        val length = parseScalar(parts[0])
        set(UiProps.BorderDash, UiBorderDash(length, parts.getOrNull(1)?.let(::parseScalar) ?: length))
    }

    property(
        "border-radius",
        summary = "Corner radius of the background, border and clip.",
        syntax = syntax(slot("radius", HssValueKind.PIXELS)),
        examples = listOf("4px", "8px", "12px"),
    ) { style { it.borderRadius = parseScalar(value) } }

    property(
        "shadows", "shadow", "box-shadow",
        summary = "Drop shadows, painted in the listed order.",
        syntax = listSyntax(
            slot("x", HssValueKind.PIXELS),
            slot("y", HssValueKind.PIXELS),
            slot("blur", HssValueKind.PIXELS),
            slot("spread", HssValueKind.PIXELS, optional = true),
            colorSlot(),
            classifier = ::shadowSlotAt,
        ),
        examples = listOf("1px 1px 3px rgba(0, 0, 0, 0.42)", "inset 0px 2px 4px 0px #00000055", "none"),
    ) { style { it.shadows = parseShadows(value) } }

    property(
        "opacity",
        summary = "Opacity of the node and its children.",
        syntax = syntax(slot("opacity", HssValueKind.NUMBER)),
        examples = listOf("1", "0.5", "0"),
    ) { style { it.opacity = value.toFloat() } }

    property(
        "tint",
        summary = "Color multiplied into everything the node draws; stacks across states.",
        syntax = syntax(colorSlot("tint")),
        examples = listOf("#FFFFFF", "rgba(255, 255, 255, 0.75)"),
    ) { set(UiProps.Tint, parseColor(value)) }

    property(
        "translate",
        summary = "Translation offset; stacks across simultaneously active states.",
        syntax = syntax(
            slot("x", HssValueKind.NUMBER),
            slot("y", HssValueKind.NUMBER, optional = true),
            slot("z", HssValueKind.NUMBER, optional = true),
        ),
        examples = listOf("0 -4", "8 0 0"),
    ) { set(UiProps.Translate, parseVec3(value)) }

    property(
        "rotate",
        summary = "Rotation in degrees around each axis; stacks across active states.",
        syntax = syntax(
            slot("x", HssValueKind.NUMBER),
            slot("y", HssValueKind.NUMBER, optional = true),
            slot("z", HssValueKind.NUMBER, optional = true),
        ),
        examples = listOf("0", "0 0 90", "0 0 180"),
    ) { set(UiProps.Rotate, parseVec3(value)) }

    property(
        "scale",
        summary = "Scale factor; a single value scales both axes.",
        syntax = syntax(
            slot("x", HssValueKind.NUMBER),
            slot("y", HssValueKind.NUMBER, optional = true),
            slot("z", HssValueKind.NUMBER, optional = true),
        ),
        examples = listOf("1", "1.05", "1 1.2"),
    ) { set(UiProps.Scale, parseScale(value)) }

    // Per-axis long-hands: a keyframe that moves one axis should not have to restate the
    // other two, and `translate-y: 18px` reads better than `translate: 0 18 0`.
    axisProperty("translate-x", "translate", "x") { vector, amount -> vector.copy(x = amount) }
    axisProperty("translate-y", "translate", "y") { vector, amount -> vector.copy(y = amount) }
    axisProperty("translate-z", "translate", "z") { vector, amount -> vector.copy(z = amount) }
    axisProperty("rotate-x", "rotate", "x") { vector, amount -> vector.copy(x = amount) }
    axisProperty("rotate-y", "rotate", "y") { vector, amount -> vector.copy(y = amount) }
    axisProperty("rotate-z", "rotate", "z") { vector, amount -> vector.copy(z = amount) }
    axisProperty("scale-x", "scale", "x") { vector, amount -> vector.copy(x = amount) }
    axisProperty("scale-y", "scale", "y") { vector, amount -> vector.copy(y = amount) }
    axisProperty("scale-z", "scale", "z") { vector, amount -> vector.copy(z = amount) }

    property(
        "transform",
        summary = "Transform function chain applied on top of the current transform.",
        syntax = syntax(slot("functions", HssValueKind.ANY, keywords = UiTransformFunctions)),
        examples = listOf("translate(0, -4px) scale(1.05)", "rotate(90deg)", "none"),
    ) { style { it.transform = parseTransform(value, it.transform ?: UiTransform()) } }

    property(
        "pivot", "transform-origin",
        summary = "Origin transforms rotate and scale around.",
        syntax = syntax(
            HssSlot("x", HssValueKind.LENGTH, keywords = UiPivotKeywords),
            slot("y", HssValueKind.LENGTH, optional = true),
            slot("z", HssValueKind.LENGTH, optional = true),
        ),
        examples = UiPivotKeywords + "50% 50%",
    ) { style { it.transform = (it.transform ?: UiTransform()).copy(pivot = parsePivot(value)) } }

    property(
        "perspective",
        summary = "Perspective distance used by 3D rotations.",
        syntax = syntax(slot("distance", HssValueKind.PIXELS)),
        examples = listOf("0", "300px"),
    ) { style { it.transform = (it.transform ?: UiTransform()).copy(perspective = parseScalar(value)) } }

    property(
        "filter",
        summary = "Effects applied to what the node draws.",
        syntax = syntax(slot("effects", HssValueKind.FILTER)),
        examples = listOf("none", "blur(4px)", "grayscale(1)"),
    ) { style { it.filter = parseFilterChain(value) } }

    property(
        "mask", "mask-image",
        summary = "Gradient the node is seen through; only the alpha of each stop is used.",
        syntax = syntax(slot("gradient", HssValueKind.FILTER)),
        examples = listOf(
            "none",
            "linear-gradient(to bottom, transparent, white 20%, white 80%, transparent)",
            "linear-gradient(90deg, white, transparent)",
        ),
    ) {
        style { current ->
            val mask = parseMask(value)
            val rest = (current.filter ?: UiFilterChain.Empty).effects
                .filterNot { effect -> effect is UiFilterEffect.LinearMask }
            current.filter = UiFilterChain(if (mask == null) rest else rest + mask)
        }
    }

    property(
        "backdrop-filter",
        summary = "Effects applied to whatever is drawn behind the node.",
        syntax = syntax(slot("effects", HssValueKind.FILTER)),
        examples = listOf("none", "blur(8px)"),
    ) { style { it.backdropFilter = parseFilterChain(value) } }

    property(
        "backface-visibility",
        summary = "Whether the node stays visible once a 3D rotation turns it away.",
        syntax = syntax(keywordSlot("visibility", *enumKeywords<UiBackfaceVisibility>().toTypedArray())),
    ) { style { it.backfaceVisibility = parseBackfaceVisibility(value) } }

    property(
        "clip", "clip-path",
        summary = "Clips children to the node bounds, or to a shape.",
        syntax = syntax(HssSlot("clip", HssValueKind.SHAPE, keywords = listOf("true", "false"))),
        examples = listOf("true", "false", "path(\"M 0 0 L 10 0 L 10 10 Z\", 10 10)"),
    ) { style { applyClip(it, value) } }

    property(
        "image-fit", "fit",
        summary = "How the image fills the node; slice fits take their inset after the keyword.",
        syntax = syntax(
            keywordSlot("fit", *UiImageFitKeywords.toTypedArray()),
            sizeSlot("slice", auto = false).copy(optional = true),
        ),
        examples = listOf("stretch", "contain", "cover", "9-slice 4px"),
    ) {
        style {
            it.imageFit = parseImageFit(value)
            parseImageFitSlice(value)?.let { slice -> it.imageSlice = slice }
        }
    }

    property(
        "image-slice", "slice",
        summary = "Nine-slice insets of the image.",
        syntax = edgesSyntax(auto = false),
        examples = listOf("4px", "4px 8px", "4px 8px 4px 8px"),
    ) { style { it.imageSlice = parseInsets(value, allowAuto = false) } }

    property(
        "image-uv", "uv",
        summary = "Part of the source texture to draw, so one atlas can back many nodes.",
        syntax = syntax(
            slot("x", HssValueKind.LENGTH, keywords = listOf("none")),
            sizeSlot("y", auto = false).copy(optional = true),
            sizeSlot("width", auto = false).copy(optional = true),
            sizeSlot("height", auto = false).copy(optional = true),
        ),
        examples = listOf("none", "16px 0px 16px 16px", "0% 0% 50% 50%"),
    ) { style { it.imageUv = parseImageUv(value) } }

    property(
        "shape",
        summary = "Vector shape drawn instead of a rectangle.",
        syntax = syntax(slot("shape", HssValueKind.SHAPE)),
        examples = listOf("path(\"M 0 0 L 7 0 L 3.5 5 Z\", 7 5)", "svg(\"hollowengine:ui/shapes/hexagon.svg\")"),
    ) { style { it.shape = parseShape(value) } }

    property(
        "shape-fill", "fill",
        summary = "Fill of the vector shape.",
        syntax = syntax(paintSlot("fill")),
    ) { style { it.shapeFill = parsePaint(value) } }

    property(
        "shape-stroke", "stroke",
        summary = "Stroke of the vector shape.",
        syntax = syntax(paintSlot("stroke")),
    ) { style { it.shapeStroke = parsePaint(value) } }

    property(
        "shape-stroke-width", "stroke-width",
        summary = "Stroke width of the vector shape.",
        syntax = syntax(sizeSlot("width", auto = false)),
        examples = listOf("1px", "2px"),
    ) { style { it.shapeStrokeWidth = parseLength(value, allowAuto = false) } }
}

/** Declares one axis of a transform vector, leaving the other two as they are. */
private fun HssPropertyBuilder.axisProperty(
    name: String,
    transform: String,
    axis: String,
    patch: (UiVec3, Float) -> UiVec3,
) {
    property(
        name,
        summary = "The $axis component of `$transform`.",
        syntax = syntax(slot(axis, HssValueKind.NUMBER)),
        examples = listOf("0", "8px", "-4"),
    ) {
        style {
            val amount = parseScalar(value)
            when (transform) {
                "translate" -> it.translate = patch(it.translate ?: UiVec3(), amount)
                "rotate" -> it.rotate = patch(it.rotate ?: UiVec3(), amount)
                else -> it.scale = patch(it.scale ?: UiVec3(1f, 1f, 1f), amount)
            }
        }
    }
}

/**
 * Declares a single-edge border. The width is per edge, the color is shared by the whole border.
 */
private fun HssPropertyBuilder.borderEdge(
    name: String,
    edge: String,
    patch: (UiInsets, UiLength) -> UiInsets,
) {
    property(
        name,
        summary = "Width of the $edge border, and the border fill.",
        syntax = syntax(sizeSlot("width", auto = false), paintSlot("color").copy(optional = true)),
        examples = listOf("1px", "1px #323846"),
    ) {
        style {
            val parts = splitTopLevelWhitespace(value)
            it.borderWidth = patch(it.borderWidth ?: UiInsets.Zero, parseLength(parts.first(), allowAuto = false))
            parts.drop(1).joinToString(" ").takeIf(String::isNotBlank)?.let { paint ->
                it.borderPaint = parsePaint(paint)
            }
        }
    }
}

/** Classifies a `shadows` entry token, mirroring how [parseShadows] reads it. */
private fun shadowSlotAt(tokens: List<String>, index: Int): HssSlot? {
    val token = tokens.getOrNull(index) ?: return null
    if (token.equals("inset", ignoreCase = true)) return ShadowInsetSlot
    if (looksLikeColor(token)) return ShadowColorSlot
    val offset = if (tokens.firstOrNull()?.equals("inset", ignoreCase = true) == true) 1 else 0
    return ShadowGeometrySlots.getOrNull(index - offset)
}

private val ShadowInsetSlot = keywordSlot("inset", "inset")
private val ShadowColorSlot = slot("color", HssValueKind.COLOR)
private val ShadowGeometrySlots = listOf(
    slot("x", HssValueKind.PIXELS),
    slot("y", HssValueKind.PIXELS),
    slot("blur", HssValueKind.PIXELS),
    slot("spread", HssValueKind.PIXELS, optional = true),
)
