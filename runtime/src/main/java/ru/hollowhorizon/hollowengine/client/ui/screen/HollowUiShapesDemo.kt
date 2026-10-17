package ru.hollowhorizon.hollowengine.client.ui.screen

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.shape.*
import ru.hollowhorizon.hollowengine.client.ui.style.UiGradientStop
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.style.UiRadialGradient
import ru.hollowhorizon.hollowengine.client.ui.style.UiShadow

@Composable
internal fun shapesDemo() {
    val bevel = GenericShape { size ->
        moveTo(0f, 0f)
        lineTo(size.width, 0f)
        lineTo(size.width - 34f, size.height)
        lineTo(0f, size.height - 18f)
        close()
    }
    val wave = GenericShape { size ->
        moveTo(8f, size.height * 0.7f)
        curveTo(size.width * 0.28f, -18f, size.width * 0.58f, size.height + 28f, size.width - 8f, size.height * 0.28f)
    }
    val radial = UiPaint.RadialGradient(
        UiRadialGradient(
            centerX = 34.percent,
            centerY = 34.percent,
            radius = 76.percent,
            stops = listOf(
                UiGradientStop(0f, UiColor(0.34f, 0.78f, 0.74f, 1f)),
                UiGradientStop(1f, UiColor(0.16f, 0.24f, 0.45f, 1f)),
            ),
        )
    )
    val svgHexagon = svgResource("hollowengine:ui/shapes/hexagon.svg")
    val svgBadge = svgResource("hollowengine:ui/shapes/badge-check.svg")
    val svgUnderline = svgResource("hollowengine:ui/shapes/underline.svg")
    val engineLogo = svgResource("hollowengine:textures/gui/logo/logo.svg")
    val nbtIcon = svgResource("hollowengine:textures/gui/icons/nbt.svg")
    val consoleIcon = svgResource("hollowengine:textures/gui/icons/console.svg")
    val lightSpotIcon = svgResourceDocument("hollowengine:textures/gui/icons/light_spot.svg")
    val pipelineSvg = svgResourceDocument("hollowengine:ui/shapes/demo-pipeline.svg")
    val questMapSvg = svgResourceDocument("hollowengine:ui/shapes/demo-quest-map.svg")
    val viewportStackSvg = svgResourceDocument("hollowengine:ui/shapes/demo-viewport-stack.svg")

    Box(tags = listOf("shapes-stage"), modifier = Modifier.scrollable()) {
        Column(tags = listOf("shape-card", "hss-path-card"), modifier = Modifier.position(20.px, 20.px)) {
            Text("HSS path", tags = listOf("card-title"))
            Text("shape + fill + stroke", tags = listOf("body"))
        }
        Box(
            tags = listOf("shape-card"),
            modifier = Modifier.position(230.px, 20.px)
                .size(188.px, 126.px)
                .drawBehind {
                    drawShape(bevel, radial)
                    drawShape(
                        bevel,
                        UiPaint.Color(UiColor(0.82f, 0.94f, 1f, 0.8f)),
                        UiDrawStyle.Stroke(2f),
                    )
                }
        ) {
            Text(
                "GenericShape",
                tags = listOf("shape-label"),
                modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER)
            )
        }
        Box(
            tags = listOf("shape-card", "shape-clip-card"),
            modifier = Modifier.position(440.px, 20.px)
                .size(188.px, 126.px)
                .clip(bevel)
                .background(
                    122f, listOf(
                        UiGradientStop(0f, UiColor(0.36f, 0.58f, 0.95f, 1f)),
                        UiGradientStop(1f, UiColor(0.18f, 0.8f, 0.64f, 1f)),
                    )
                )
        ) {
            Box(
                tags = listOf("shape-clip-stripe", "shape-clip-stripe-a"),
                modifier = Modifier.position((-18).px, 18.px).size(236.px, 24.px)
            )
            Box(
                tags = listOf("shape-clip-stripe", "shape-clip-stripe-b"),
                modifier = Modifier.position(22.px, 54.px).size(190.px, 22.px)
            )
            Box(
                tags = listOf("shape-clip-stripe", "shape-clip-stripe-c"),
                modifier = Modifier.then(Modifier.position((-24).px, 92.px).size(242.px, 24.px)),
            )
            Text(
                "Clip + children",
                tags = listOf("shape-label"),
                modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER)
            )
        }
        Box(
            modifier = Modifier.position(440.px, 20.px)
                .size(188.px, 126.px)
                .shape(
                    bevel,
                    fill = UiPaint.None,
                    stroke = UiPaint.Color(UiColor(0.82f, 0.94f, 1f, 0.82f)),
                    strokeWidth = 2.px
                )
        ) {
        }
        Box(
            modifier = Modifier.position(650.px, 20.px)
                .size(188.px, 126.px)
                .drawBehind {
                    drawRect(
                        paint = UiPaint.LinearGradient(
                            125f,
                            listOf(
                                UiGradientStop(0f, UiColor(0.22f, 0.72f, 0.92f, 0.96f)),
                                UiGradientStop(1f, UiColor(0.42f, 0.22f, 0.72f, 0.96f)),
                            ),
                        ),
                        radius = 28f,
                        border = UiBorder(width = UiInsets.all(2.px), color = UiColor(0.88f, 0.96f, 1f)),
                    )
                    drawRect(
                        rect = UiRect(18f, 88f, 152f, 18f),
                        paint = UiPaint.Color(UiColor(0.08f, 0.12f, 0.2f, 0.72f)),
                        radius = 9f,
                    )
                },
        ) {
            Text("Canvas SDF rect", tags = listOf("shape-label"), modifier = Modifier.position(28.px, 18.px))
        }
        Box(
            tags = listOf("shape-card"),
            modifier = Modifier.position(20.px, 176.px)
                .size(398.px, 112.px)
                .shape(
                    wave,
                    fill = UiPaint.None,
                    stroke = UiPaint.Color(UiColor(0.72f, 0.9f, 1f, 1f)),
                    strokeWidth = 5.px
                )
        ) {
            Text("Stroke-only curve", tags = listOf("shape-label"), modifier = Modifier.position(142.px, 10.px))
        }
        Box(
            tags = listOf("shape-card", "svg-file-hexagon"),
            modifier = Modifier.position(440.px, 176.px),
        ) {
            Text(
                "HSS svg(...)",
                tags = listOf("shape-label"),
                modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER)
            )
        }
        Box(
            tags = listOf("shape-card"),
            modifier = Modifier.position(650.px, 176.px)
                .size(188.px, 126.px)
                .shape(
                    svgBadge,
                    fill = UiPaint.None,
                    stroke = UiPaint.Color(UiColor(0.72f, 0.9f, 1f, 1f)),
                    strokeWidth = 2.px
                )
        ) {
            Text("SVG stroke", tags = listOf("shape-label"), modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER))
        }
        Box(
            tags = listOf("shape-card", "svg-clip-card"),
            modifier = Modifier.position(650.px, 320.px)
                .size(188.px, 126.px)
                .clip(svgHexagon)
                .background(
                    135f, listOf(
                        UiGradientStop(0f, UiColor(0.92f, 0.58f, 0.36f, 1f)),
                        UiGradientStop(1f, UiColor(0.26f, 0.84f, 0.75f, 1f)),
                    )
                )
        ) {
            Box(
                tags = listOf("shape-clip-stripe", "shape-clip-stripe-a"),
                modifier = Modifier.position((-14).px, 26.px).size(230.px, 24.px),
            )
            Box(
                tags = listOf("shape-clip-stripe", "shape-clip-stripe-b"),
                modifier = Modifier.position(18.px, 68.px).size(198.px, 22.px),
            )
            Text("SVG clip", tags = listOf("shape-label"), modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER))
        }
        Box(
            modifier = Modifier.position(650.px, 320.px)
                .size(188.px, 126.px)
                .shape(
                    svgHexagon,
                    fill = UiPaint.None,
                    stroke = UiPaint.Color(UiColor(0.94f, 1f, 0.9f, 0.84f)),
                    strokeWidth = 2.px
                )
        ) {
        }
        Box(
            tags = listOf("shape-card"),
            modifier = Modifier.position(20.px, 320.px)
                .size(398.px, 64.px)
                .shape(
                    svgUnderline,
                    fill = UiPaint.None,
                    stroke = UiPaint.Color(UiColor(1f, 0.86f, 0.42f, 1f)),
                    strokeWidth = 5.px
                )
        ) {
            Text("SVG file underline", tags = listOf("shape-label"), modifier = Modifier.position(142.px, 6.px))
        }
        Box(
            tags = listOf("shape-card"),
            modifier = Modifier.position(440.px, 320.px)
                .size(188.px, 126.px)
                .shape(
                    engineLogo,
                    fill = UiPaint.RadialGradient(
                        UiRadialGradient(
                            centerX = 38.percent,
                            centerY = 34.percent,
                            radius = 72.percent,
                            stops = listOf(
                                UiGradientStop(0f, UiColor(1f, 0.72f, 0.28f, 0.96f)),
                                UiGradientStop(1f, UiColor(0.2f, 0.5f, 0.9f, 0.9f)),
                            ),
                        )
                    ),
                    stroke = UiPaint.Color(UiColor(0.96f, 0.98f, 1f, 0.72f)),
                    strokeWidth = 1.px,
                )
        ) {
            Text(
                "Engine logo SVG",
                tags = listOf("shape-label"),
                modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER)
            )
        }
        SvgDemoCard(
            label = "Existing text SVG",
            shape = nbtIcon,
            x = 20,
            y = 464,
            fill = UiPaint.Color(UiColor(0.92f, 1f, 0.78f, 0.95f)),
            stroke = UiPaint.Color(UiColor(0.12f, 0.16f, 0.22f, 0.72f)),
            strokeWidth = 1.px,
        )
        SvgDemoCard(
            label = "Existing clip SVG",
            shape = consoleIcon,
            x = 230,
            y = 464,
            fill = UiPaint.LinearGradient(
                220f,
                listOf(
                    UiGradientStop(0f, UiColor(0.82f, 0.94f, 1f, 0.96f)),
                    UiGradientStop(1f, UiColor(0.34f, 0.78f, 0.74f, 0.92f)),
                ),
            ),
            stroke = UiPaint.Color(UiColor(0.96f, 0.98f, 1f, 0.6f)),
            strokeWidth = 1.px,
        )
        SvgDocumentDemoCard(
            label = "Existing round stroke",
            document = lightSpotIcon,
            x = 440,
            y = 464,
            shadow = UiShadow(
                offset = UiVec3(5f, 6f),
                blur = 6f,
                spread = 1f,
                color = UiColor(0f, 0f, 0f, 0.75f),
            ),
        )
        SvgDocumentDemoCard(
            label = "Custom pipeline",
            document = pipelineSvg,
            x = 20,
            y = 608,
            width = 398,
        )
        SvgDocumentDemoCard(
            label = "Custom quest map",
            document = questMapSvg,
            x = 440,
            y = 608,
        )
        SvgDocumentDemoCard(
            label = "Custom viewport",
            document = viewportStackSvg,
            x = 650,
            y = 608,
        )
    }
}

@Composable
private fun SvgDocumentDemoCard(
    label: String,
    document: UiSvgPathDocument,
    x: Int,
    y: Int,
    width: Int = 188,
    height: Int = 126,
    shadow: UiShadow? = null,
) {
    var cardModifier: Modifier = Modifier.position(x.px, y.px).size(width.px, height.px)
    if (shadow != null) {
        cardModifier = cardModifier
            .shape(SvgPathShape(document.path, document.viewBox), UiPaint.None)
            .shadow(shadow)
    }
    Box(
        tags = listOf("shape-card"),
        modifier = cardModifier.drawBehind { drawSvg(document) },
    ) {
        Text(label, tags = listOf("shape-label"), modifier = Modifier.position(14.px, 8.px))
    }
}

@Composable
private fun SvgDemoCard(
    label: String,
    shape: Shape,
    x: Int,
    y: Int,
    width: Int = 188,
    height: Int = 126,
    fill: UiPaint = UiPaint.Color(UiColor(0.82f, 0.94f, 1f, 0.9f)),
    stroke: UiPaint? = UiPaint.Color(UiColor(0.96f, 0.98f, 1f, 0.62f)),
    strokeWidth: UiLength = 1.px,
) {
    Box(
        tags = listOf("shape-card"),
        modifier = Modifier.position(x.px, y.px)
            .size(width.px, height.px)
            .drawBehind {
                if (fill != UiPaint.None) drawShape(shape, fill)
                if (stroke != null && stroke != UiPaint.None) {
                    drawShape(shape, stroke, UiDrawStyle.Stroke(strokeWidth.resolve(size.width)))
                }
            },
    ) {
        Text(label, tags = listOf("shape-label"), modifier = Modifier.position(14.px, 8.px))
    }
}
