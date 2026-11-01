package ru.hollowhorizon.hollowengine.client.ui.shape

import net.minecraft.resources.ResourceLocation
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import ru.hollowhorizon.hollowengine.client.ui.HollowUiResourceAccess
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.font.FontRenderContext
import java.io.StringReader
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

data class UiSvgPathDocument(
    val path: UiPath,
    val viewBox: UiRect,
    val elements: List<UiSvgPathElement> = listOf(UiSvgPathElement(path)),
)

data class SvgResourceShape(
    val location: ResourceLocation,
) : Shape {
    override fun createPath(size: UiShapeSize): UiPath {
        val document = UiSvgResourceLoader.load(location)
        return SvgPathShape(document.path, document.viewBox).createPath(size)
    }
}

object UiSvgResourceLoader {
    private val cache = mutableMapOf<ResourceLocation, CachedSvgDocument>()

    fun load(location: ResourceLocation): UiSvgPathDocument {
        val version = HollowUiResourceAccess.version(location)
        val cached = cache[location]
        if (cached != null && cached.version == version) return cached.document

        val document = SvgFileParser.parse(HollowUiResourceAccess.readText(location), location)
        cache[location] = CachedSvgDocument(version, document)
        return document
    }

    private data class CachedSvgDocument(
        val version: Long,
        val document: UiSvgPathDocument,
    )
}

object SvgFileParser {
    fun parse(source: String): UiSvgPathDocument = parse(source, baseLocation = null)

    internal fun parse(source: String, baseLocation: ResourceLocation?): UiSvgPathDocument {
        return SvgParseSession(source, baseLocation, emptySet()).parse()
    }
}

fun svgResource(location: ResourceLocation): Shape = SvgResourceShape(location)
fun svgResource(location: String): Shape = SvgResourceShape(parseSvgResourceLocation(location))

fun svgResourceDocument(location: ResourceLocation): UiSvgPathDocument = UiSvgResourceLoader.load(location)
fun svgResourceDocument(location: String): UiSvgPathDocument = svgResourceDocument(parseSvgResourceLocation(location))

private class SvgParseSession(
    source: String,
    private val baseLocation: ResourceLocation?,
    private val externalStack: Set<ResourceLocation>,
) {
    private val root = parseRoot(source)
    private val cssRules = parseSvgCssRules(root)
    private val idIndex = buildIdIndex(root)
    private val viewBox = parseViewBox(root)

    fun parse(): UiSvgPathDocument {
        require(root.svgName() == "svg") { "Expected <svg> root, got <${root.tagName}>" }
        val elements = collect(root, SvgContext(isRoot = true))
        val path = combineSvgPaths(elements.map { it.path })
        require(!path.isEmpty()) { "SVG file does not contain drawable geometry" }
        return UiSvgPathDocument(path = path, viewBox = viewBox, elements = elements)
    }

    private fun collect(element: Element, context: SvgContext): List<UiSvgPathElement> {
        val name = element.svgName()
        if (name in ignoredElements) return emptyList()
        if (name in definitionElements && context.renderDefinitions.not()) return emptyList()

        val style = element.resolveSvgStyle(context.style, cssRules)
        if (!style.display || !style.visibility || style.opacity <= 0f) return emptyList()

        val transform = context.transform * element.localTransform(context.isRoot)
        val next = context.copy(style = style, transform = transform, isRoot = false)
        return when (name) {
            "path" -> pathElement(element, next)
            "rect" -> primitiveElement(element, next) { rectPrimitive(element) }
            "circle" -> primitiveElement(element, next) { circlePrimitive(element) }
            "ellipse" -> primitiveElement(element, next) { ellipsePrimitive(element) }
            "line" -> primitiveElement(element, next) { linePrimitive(element) }
            "polyline" -> primitiveElement(element, next) { pointsPrimitive(element, close = false) }
            "polygon" -> primitiveElement(element, next) { pointsPrimitive(element, close = true) }
            "text" -> textElement(element, next)
            "image", "foreignobject" -> primitiveElement(element, next) { boxPrimitive(element) }
            "use" -> useElement(element, next)
            "svg", "g", "symbol", "clippath", "mask", "a" -> collectChildren(element, next)
            "filter", "lineargradient", "radialgradient", "pattern", "marker" -> emptyList()
            else -> collectChildren(element, next)
        }
    }

    private fun pathElement(element: Element, context: SvgContext): List<UiSvgPathElement> {
        val data = element.getAttribute("d").trim()
        require(data.isNotEmpty()) { "SVG <path> element requires non-empty d attribute" }
        return listOfElement(element, context, SvgPathParser.parse(data))
    }

    private fun primitiveElement(
        element: Element,
        context: SvgContext,
        builder: () -> UiPath,
    ): List<UiSvgPathElement> {
        return listOfElement(element, context, builder())
    }

    private fun textElement(element: Element, context: SvgContext): List<UiSvgPathElement> {
        val text = element.textContent.orEmpty()
        if (text.isBlank()) return emptyList()
        val font = resolveSvgTextFont(context.style.fontFamily, context.style.fontSize)
        val vector = font.createGlyphVector(fontRenderContext, text)
        val bounds = vector.visualBounds
        val x = element.svgLength("x") ?: 0f
        val y = element.svgLength("y") ?: 0f
        val anchorOffset = when (context.style.textAnchor) {
            UiSvgTextAnchor.START -> 0.0
            UiSvgTextAnchor.MIDDLE -> -bounds.width * 0.5 - bounds.x
            UiSvgTextAnchor.END -> -bounds.width - bounds.x
        }
        val path = vector.getOutline((x + anchorOffset).toFloat(), y).toUiPath()
        return listOfElement(element, context, path)
    }

    private fun useElement(element: Element, context: SvgContext): List<UiSvgPathElement> {
        val href = element.href()
        require(href.isNotEmpty()) { "SVG <use> requires href or xlink:href" }

        val x = element.svgLength("x") ?: 0f
        val y = element.svgLength("y") ?: 0f
        val useTransform = context.transform * UiSvgTransform.translation(x, y)
        val referenceContext = context.copy(transform = useTransform)
        return collectReference(
            href = href,
            context = referenceContext,
            viewportWidth = element.svgLength("width"),
            viewportHeight = element.svgLength("height"),
        )
    }

    private fun collectReference(
        href: String,
        context: SvgContext,
        viewportWidth: Float? = null,
        viewportHeight: Float? = null,
    ): List<UiSvgPathElement> {
        val reference = SvgReference.parse(href)
        if (reference.locationPart == null) {
            val id = reference.id ?: throw IllegalArgumentException("SVG reference '$href' does not contain an id")
            val target = idIndex[id] ?: throw IllegalArgumentException("SVG reference '$href' was not found")
            val key = "${baseLocation.orEmptyKey()}#$id"
            require(key !in context.referenceStack) { "Circular SVG reference '$href'" }
            val transform = context.transform * target.viewBoxTransform(viewportWidth, viewportHeight)
            return collect(
                target,
                context.copy(
                    transform = transform,
                    referenceStack = context.referenceStack + key,
                    renderDefinitions = true
                )
            )
        }

        val location = resolveExternalLocation(reference.locationPart)
        require(location !in externalStack) { "Circular external SVG reference '$href'" }
        val external = SvgParseSession(
            source = HollowUiResourceAccess.readText(location),
            baseLocation = location,
            externalStack = externalStack + location,
        )
        return external.collectExternal(reference.id, context, viewportWidth, viewportHeight)
    }

    private fun collectExternal(
        id: String?,
        context: SvgContext,
        viewportWidth: Float?,
        viewportHeight: Float?,
    ): List<UiSvgPathElement> {
        val target = id?.let { idIndex[it] } ?: root
        val transform = context.transform * target.viewBoxTransform(viewportWidth, viewportHeight)
        return collect(target, context.copy(transform = transform, isRoot = target == root, renderDefinitions = true))
    }

    private fun listOfElement(element: Element, context: SvgContext, sourcePath: UiPath): List<UiSvgPathElement> {
        if (sourcePath.isEmpty()) return emptyList()
        val id = element.svgId()
        val result = mutableListOf<UiSvgPathElement>()
        val bounds = sourcePath.bounds()

        val fill = svgPaint(context.style.fillRef, context, bounds, context.style.fillAlpha())
            ?: context.style.fillColor()?.let(UiPaint::Color)
        if (fill != null) {
            appendElementPath(result, sourcePath.withFillRule(context.style.fillRule), context, id, fill)
        }

        val strokePath = sourcePath.toSvgStrokePath(context.style)
        val stroke = svgPaint(context.style.strokeRef, context, bounds, context.style.strokeAlpha())
            ?: context.style.strokeColor()?.let(UiPaint::Color)
        if (strokePath != null && stroke != null) {
            appendElementPath(result, strokePath, context, id, stroke)
        }

        return result
    }

    private fun svgPaint(reference: String?, context: SvgContext, bounds: UiRect?, alpha: Float): UiPaint? =
        resolveSvgPaint(
            reference = reference,
            viewBox = viewBox,
            transform = context.transform,
            objectBounds = bounds,
            alpha = alpha,
            elementById = idIndex::get,
        )

    private fun appendElementPath(
        result: MutableList<UiSvgPathElement>,
        sourcePath: UiPath,
        context: SvgContext,
        id: String?,
        paint: UiPaint,
    ) {
        val transformed = sourcePath.transformed(context.transform)
        val clipped = applyClipAndMask(transformed, context)
        if (!clipped.isEmpty()) {
            result += UiSvgPathElement(
                path = clipped,
                style = context.style,
                id = id,
                paint = paint,
                filterEffects = parseSvgFilterEffects(context.style.filter, idIndex::get),
            )
        }
    }

    private fun applyClipAndMask(path: UiPath, context: SvgContext): UiPath {
        val clipReference = parseUrlReference(context.style.clipPath)
        val maskReference = parseUrlReference(context.style.mask)
        var result = path
        if (clipReference != null) referencePathOrNull(clipReference, context, luminance = false)?.let {
            result = result.intersectedWith(it)
        }
        if (maskReference != null) referencePathOrNull(maskReference, context, luminance = true)?.let {
            result = result.intersectedWith(it)
        }
        return result
    }

    private fun referencePathOrNull(href: String, context: SvgContext, luminance: Boolean): UiPath? {
        return runCatching {
            val elements = collectReference(href, context.copy(style = context.style.withoutGeometryEffects()))
            if (!luminance) combineSvgPaths(elements.map { it.path })
            else combineSvgMask(elements.map { it.path to (it.paint as? UiPaint.Color)?.color.orWhite() })
        }.getOrElse { error ->
            if (error.message?.contains("was not found") == true) null else throw error
        }
    }

    private fun UiColor?.orWhite(): UiColor = this ?: UiColor.White

    private fun collectChildren(element: Element, context: SvgContext): List<UiSvgPathElement> {
        val paths = mutableListOf<UiSvgPathElement>()
        var child = element.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) paths += collect(child as Element, context)
            child = child.nextSibling
        }
        return paths
    }

    private fun rectPrimitive(element: Element): UiPath {
        val x = element.svgLength("x") ?: 0f
        val y = element.svgLength("y") ?: 0f
        val width = element.svgLength("width") ?: 0f
        val height = element.svgLength("height") ?: 0f
        val rx = (element.svgLength("rx") ?: element.svgLength("ry") ?: 0f).coerceIn(0f, width * 0.5f)
        val ry = (element.svgLength("ry") ?: element.svgLength("rx") ?: 0f).coerceIn(0f, height * 0.5f)
        if (rx <= 0f || ry <= 0f) return rectPath(x, y, width, height)
        return path {
            moveTo(x + rx, y)
            lineTo(x + width - rx, y)
            ellipticalArcTo(rx, ry, 0f, largeArc = false, sweep = true, x + width, y + ry)
            lineTo(x + width, y + height - ry)
            ellipticalArcTo(rx, ry, 0f, largeArc = false, sweep = true, x + width - rx, y + height)
            lineTo(x + rx, y + height)
            ellipticalArcTo(rx, ry, 0f, largeArc = false, sweep = true, x, y + height - ry)
            lineTo(x, y + ry)
            ellipticalArcTo(rx, ry, 0f, largeArc = false, sweep = true, x + rx, y)
            close()
        }
    }

    private fun circlePrimitive(element: Element): UiPath {
        val centerX = element.svgLength("cx") ?: 0f
        val centerY = element.svgLength("cy") ?: 0f
        val radius = element.svgLength("r") ?: 0f
        return ellipsePrimitive(centerX, centerY, radius, radius)
    }

    private fun ellipsePrimitive(element: Element): UiPath {
        val centerX = element.svgLength("cx") ?: 0f
        val centerY = element.svgLength("cy") ?: 0f
        val radiusX = element.svgLength("rx") ?: 0f
        val radiusY = element.svgLength("ry") ?: 0f
        return ellipsePrimitive(centerX, centerY, radiusX, radiusY)
    }

    private fun ellipsePrimitive(centerX: Float, centerY: Float, radiusX: Float, radiusY: Float): UiPath {
        if (radiusX <= 0f || radiusY <= 0f) return UiPath.Empty
        return path {
            moveTo(centerX + radiusX, centerY)
            ellipticalArcTo(radiusX, radiusY, 0f, largeArc = false, sweep = true, centerX - radiusX, centerY)
            ellipticalArcTo(radiusX, radiusY, 0f, largeArc = false, sweep = true, centerX + radiusX, centerY)
            close()
        }
    }

    private fun linePrimitive(element: Element): UiPath {
        val x1 = element.svgLength("x1") ?: 0f
        val y1 = element.svgLength("y1") ?: 0f
        val x2 = element.svgLength("x2") ?: 0f
        val y2 = element.svgLength("y2") ?: 0f
        return path {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
    }

    private fun pointsPrimitive(element: Element, close: Boolean): UiPath {
        val points = parseSvgNumbers(element.getAttribute("points"))
        require(points.size >= 4 && points.size % 2 == 0) { "SVG points attribute expects x/y pairs" }
        return path {
            moveTo(points[0], points[1])
            var index = 2
            while (index < points.size) {
                lineTo(points[index], points[index + 1])
                index += 2
            }
            if (close) close()
        }
    }

    private fun boxPrimitive(element: Element): UiPath {
        val x = element.svgLength("x") ?: 0f
        val y = element.svgLength("y") ?: 0f
        val width = element.svgLength("width") ?: 0f
        val height = element.svgLength("height") ?: 0f
        return rectPath(x, y, width, height)
    }

    private fun Element.localTransform(isRoot: Boolean): UiSvgTransform {
        var transform = UiSvgTransform.Identity
        if (!isRoot && svgName() == "svg") {
            val x = svgLength("x") ?: 0f
            val y = svgLength("y") ?: 0f
            transform *= UiSvgTransform.translation(x, y) * viewBoxTransform(svgLength("width"), svgLength("height"))
        }
        if (hasNonEmptyAttribute("transform")) transform *= parseSvgTransform(getAttribute("transform"))
        val styleTransform = getAttribute("style").split(';').firstOrNull { it.trim().startsWith("transform:") }
            ?.substringAfter(':')
        if (!styleTransform.isNullOrBlank()) transform *= parseSvgTransform(styleTransform)
        return transform
    }

    private fun Element.viewBoxTransform(viewportWidth: Float?, viewportHeight: Float?): UiSvgTransform {
        val viewBox = parseOptionalViewBox(this) ?: return UiSvgTransform.Identity
        val width = viewportWidth ?: svgLength("width") ?: viewBox.width
        val height = viewportHeight ?: svgLength("height") ?: viewBox.height
        val scaleX = width / viewBox.width.coerceAtLeast(0.0001f)
        val scaleY = height / viewBox.height.coerceAtLeast(0.0001f)
        return UiSvgTransform.scale(scaleX, scaleY) * UiSvgTransform.translation(-viewBox.x, -viewBox.y)
    }

    private fun resolveExternalLocation(locationPart: String): ResourceLocation {
        val clean = locationPart.trim()
        if (clean.contains(":")) return ResourceLocation.parse(clean)
        val base = baseLocation
            ?: throw IllegalArgumentException("External SVG reference '$clean' requires resource location context")
        val baseDirectory = base.path.substringBeforeLast('/', "")
        val path = if (baseDirectory.isEmpty()) clean else "$baseDirectory/$clean"
        return ResourceLocation.fromNamespaceAndPath(base.namespace, path)
    }
}

private data class SvgContext(
    val style: UiSvgStyle = UiSvgStyle.Default,
    val transform: UiSvgTransform = UiSvgTransform.Identity,
    val referenceStack: Set<String> = emptySet(),
    val isRoot: Boolean = false,
    val renderDefinitions: Boolean = false,
)

private data class SvgReference(
    val locationPart: String?,
    val id: String?,
) {
    companion object {
        fun parse(href: String): SvgReference {
            val clean = href.trim()
            val hash = clean.indexOf('#')
            if (hash < 0) return SvgReference(clean.takeIf(String::isNotEmpty), null)
            return SvgReference(
                locationPart = clean.substring(0, hash).takeIf(String::isNotEmpty),
                id = clean.substring(hash + 1).takeIf(String::isNotEmpty),
            )
        }
    }
}

private fun parseRoot(source: String): Element {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        isXIncludeAware = false
        disableFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        disableFeature("http://xml.org/sax/features/external-general-entities", false)
        disableFeature("http://xml.org/sax/features/external-parameter-entities", false)
        disableFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }
    return factory.newDocumentBuilder()
        .parse(InputSource(StringReader(source)))
        .documentElement
}

private fun DocumentBuilderFactory.disableFeature(name: String, value: Boolean) {
    runCatching { setFeature(name, value) }
}

private fun buildIdIndex(root: Element): Map<String, Element> {
    val elements = linkedMapOf<String, Element>()
    fun visit(element: Element) {
        element.svgId()?.let { elements[it] = element }
        var child = element.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) visit(child as Element)
            child = child.nextSibling
        }
    }
    visit(root)
    return elements
}

private fun parseViewBox(root: Element): UiRect {
    parseOptionalViewBox(root)?.let { return it }
    val width = root.svgLength("width")
    val height = root.svgLength("height")
    require(width != null && height != null) { "SVG file requires viewBox or numeric width and height" }
    return UiRect(0f, 0f, width, height)
}

private fun parseOptionalViewBox(element: Element): UiRect? {
    val numbers = parseSvgNumbers(element.getAttribute("viewBox"))
    if (numbers.isEmpty()) return null
    require(numbers.size == 4) { "SVG viewBox expects four numbers" }
    return UiRect(numbers[0], numbers[1], numbers[2], numbers[3])
}

private fun Element.href(): String {
    return getAttribute("href").ifBlank { getAttribute("xlink:href") }.trim()
}

private fun ResourceLocation?.orEmptyKey(): String {
    return this?.toString().orEmpty()
}

private fun UiSvgStyle.withoutGeometryEffects(): UiSvgStyle {
    return copy(clipPath = null, mask = null, filter = null)
}

internal fun parseSvgResourceLocation(location: String): ResourceLocation {
    val trimmed = location.trim()
    return ResourceLocation.parse(if (trimmed.contains(":")) trimmed else "hollowengine:$trimmed")
}

internal fun resolveSvgTextFont(fontFamily: String, fontSize: Float): Font {
    val size = fontSize.roundToInt().coerceAtLeast(1)
    return Font(SvgTextFontResolver.resolveFamily(fontFamily), Font.PLAIN, size)
}

private object SvgTextFontResolver {
    private val availableFamilies by lazy {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .availableFontFamilyNames
            .associateBy { it.normalizedFontFamilyKey() }
    }

    fun resolveFamily(fontFamily: String): String {
        parseFontFamilyList(fontFamily).forEach { family ->
            val key = family.normalizedFontFamilyKey()
            genericFamilies[key]?.let { return it }
            availableFamilies[key]?.let { return it }
            inferredGenericFamily(key)?.let { return it }
        }
        return Font.SERIF
    }

    private fun parseFontFamilyList(value: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        value.forEach { char ->
            when {
                quote != null -> {
                    if (char == quote) quote = null else current.append(char)
                }

                char == '\'' || char == '"' -> quote = char
                char == ',' -> {
                    current.normalizedFontFamily()?.let(result::add)
                    current.clear()
                }

                else -> current.append(char)
            }
        }
        current.normalizedFontFamily()?.let(result::add)
        return result
    }

    private fun StringBuilder.normalizedFontFamily(): String? {
        return toString().trim().trim('"', '\'').takeIf { it.isNotBlank() }
    }
}

private fun String.normalizedFontFamilyKey(): String {
    return trim().trim('"', '\'').lowercase(Locale.ROOT)
}

private fun inferredGenericFamily(key: String): String? {
    return when {
        key.contains("mono") || key.contains("code") || key.contains("console") -> Font.MONOSPACED
        key.contains("sans") || key.contains("arial") || key.contains("inter") || key.contains("roboto") -> Font.SANS_SERIF
        key.contains("serif") || key.contains("times") || key.contains("georgia") -> Font.SERIF
        else -> null
    }
}

private val genericFamilies = mapOf(
    "serif" to Font.SERIF,
    "sans-serif" to Font.SANS_SERIF,
    "sans" to Font.SANS_SERIF,
    "monospace" to Font.MONOSPACED,
    "monospaced" to Font.MONOSPACED,
    "cursive" to Font.SERIF,
    "fantasy" to Font.SANS_SERIF,
    "system-ui" to Font.SANS_SERIF,
)

private val ignoredElements = setOf("desc", "metadata", "style", "title", "stop")

private val definitionElements = setOf("defs", "clippath", "mask", "symbol")
private val fontRenderContext = FontRenderContext(null, true, true)
