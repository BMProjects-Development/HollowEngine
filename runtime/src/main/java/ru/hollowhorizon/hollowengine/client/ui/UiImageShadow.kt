package ru.hollowhorizon.hollowengine.client.ui

import ru.hollowhorizon.hollowengine.client.ui.layout.UiLayoutNode
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.shape.Shape
import ru.hollowhorizon.hollowengine.client.ui.style.*

/** Image geometry and alpha-affecting decoration, before the node's transform and opacity. */
data class UiImageShadow(
    val source: String,
    val rect: UiRect,
    val fit: UiImageFit,
    val slice: UiInsets,
    val tintAlpha: Float,
    val clipRect: UiRect?,
    val radius: Float,
    val clipShape: Shape?,
    val filter: UiFilterChain,
    val uv: UiImageUv = UiImageUv.Full,
)

internal fun imageShadow(style: UiComputedStyle, layout: UiLayoutNode): UiImageShadow? {
    val content = style.image
    val source = content ?: (style.background as? UiPaint.Image)?.source?.takeIf { style.shape == null } ?: return null
    val rect = if (content != null) UiRect(
        layout.content.x - layout.rect.x, layout.content.y - layout.rect.y,
        layout.content.width, layout.content.height,
    ) else UiRect(0f, 0f, layout.rect.width, layout.rect.height)
    return UiImageShadow(
        source, rect,
        if (content != null && source.endsWith(".svg", true)) UiImageFit.CONTAIN else style.imageFit,
        style.imageSlice, style.tint.alpha,
        clipRect = when {
            style.clip && style.clipShape == null && content != null -> rect
            style.clip || layout.needsFramebuffer && style.border.radius > 0f -> UiRect(0f, 0f, layout.rect.width, layout.rect.height)
            else -> null
        },
        radius = if (layout.needsFramebuffer) style.border.radius else 0f,
        clipShape = style.clipShape.takeIf { style.clip },
        filter = style.filter.withoutBlur(),
        uv = style.imageUv,
    )
}
