#version 330 core

uniform samplerBuffer RecordBuffer;
uniform samplerBuffer PaintBuffer;
uniform samplerBuffer StopBuffer;

const int MAX_GLYPH_PAGES = 8;

uniform sampler2D FontAtlas0;
uniform sampler2D FontAtlas1;
uniform sampler2D FontAtlas2;
uniform sampler2D FontAtlas3;
uniform sampler2D FontAtlas4;
uniform sampler2D FontAtlas5;
uniform sampler2D FontAtlas6;
uniform sampler2D FontAtlas7;
uniform float GlyphDistanceRange[MAX_GLYPH_PAGES];
uniform vec2 GlyphAtlasSize[MAX_GLYPH_PAGES];
uniform float GlyphEdgeSoftness;

in vec2 localPosition;
in vec2 clipPosition;
in vec2 glyphUv;
flat in int recordIndex;

layout(location = 0) out vec4 fragColor;

const int PAINT_SOLID = 0;
const int PAINT_LINEAR_GRADIENT = 1;
const int PAINT_RADIAL_GRADIENT = 2;

const float GLYPH_MARKER = -1.0;

const int GLYPH_MSDF = 0;
const int GLYPH_ALPHA = 1;
const int GLYPH_COLOR = 2;

float median3(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

vec4 sampleGlyphAtlas(int page, vec2 uv) {
    if (page == 1) return textureLod(FontAtlas1, uv, 0.0);
    if (page == 2) return textureLod(FontAtlas2, uv, 0.0);
    if (page == 3) return textureLod(FontAtlas3, uv, 0.0);
    if (page == 4) return textureLod(FontAtlas4, uv, 0.0);
    if (page == 5) return textureLod(FontAtlas5, uv, 0.0);
    if (page == 6) return textureLod(FontAtlas6, uv, 0.0);
    if (page == 7) return textureLod(FontAtlas7, uv, 0.0);
    return textureLod(FontAtlas0, uv, 0.0);
}

float roundedRectDistance(vec2 point, vec2 size, float radius) {
    vec2 halfSize = size * 0.5;
    float resolvedRadius = min(radius, min(halfSize.x, halfSize.y));
    vec2 offset = abs(point - halfSize) - (halfSize - resolvedRadius);
    return length(max(offset, 0.0)) + min(max(offset.x, offset.y), 0.0) - resolvedRadius;
}

float outlinePosition(vec2 point, vec2 size, float radius, float inset) {
    const float QUARTER = 1.5707963;
    vec2 halfSize = size * 0.5 - inset;
    float r = clamp(radius - inset, 0.0, min(halfSize.x, halfSize.y));
    vec2 c = halfSize - r;
    vec2 q = point - size * 0.5;
    float top = 2.0 * c.x;
    float side = 2.0 * c.y;
    float arc = r * QUARTER;
    if (q.x > c.x && q.y < -c.y) return top + r * (atan(q.y + c.y, q.x - c.x) + QUARTER);
    if (q.x > c.x && q.y > c.y) return top + arc + side + r * atan(q.y - c.y, q.x - c.x);
    if (q.x < -c.x && q.y > c.y) return 2.0 * top + 2.0 * arc + side + r * (atan(q.y - c.y, q.x + c.x) - QUARTER);
    if (q.x < -c.x && q.y < -c.y) return 2.0 * top + 3.0 * arc + 2.0 * side + r * (atan(q.y + c.y, q.x + c.x) + 2.0 * QUARTER);
    if (halfSize.y - abs(q.y) < halfSize.x - abs(q.x)) {
        return q.y < 0.0 ? q.x + c.x : top + 2.0 * arc + side + (c.x - q.x);
    }
    return q.x > 0.0 ? top + arc + (q.y + c.y) : 2.0 * top + 3.0 * arc + side + (c.y - q.y);
}

float localPixelSize() {
    vec2 dx = dFdx(localPosition);
    vec2 dy = dFdy(localPosition);
    return max(sqrt(0.5 * (dot(dx, dx) + dot(dy, dy))), 0.0001);
}

float dashCoverage(float position, float period, float dashLength, float pixelSize) {
    float phase = mod(position, period);
    float edge = min(phase, dashLength - phase);
    return clamp(edge / min(pixelSize, 2.0) + 0.5, 0.0, 1.0);
}

float sdfCoverage(float distance, float pixelSize) {
    return clamp(0.5 - distance / pixelSize, 0.0, 1.0);
}

float softenedCoverage(float edge, float blurRadius, float pixelSize) {
    float aa = pixelSize;
    if (blurRadius <= 0.0001) return clamp(0.5 - edge / aa, 0.0, 1.0);
    float extent = max(blurRadius * 2.0, aa);
    return 1.0 - smoothstep(-extent, extent, edge);
}

vec4 gradientStop(int stopIndex) {
    return texelFetch(StopBuffer, stopIndex * 2);
}

vec4 sampleStops(int stopStart, int stopCount, float offset) {
    if (stopCount <= 0) return vec4(0.0);
    if (stopCount == 1) return gradientStop(stopStart);
    float clampedOffset = clamp(offset, 0.0, 1.0);
    int left = stopStart;
    int right = stopStart + stopCount - 1;
    for (int index = 0; index < stopCount; index++) {
        int candidate = stopStart + index;
        float candidateOffset = texelFetch(StopBuffer, candidate * 2 + 1).x;
        if (candidateOffset <= clampedOffset) left = candidate;
        if (candidateOffset >= clampedOffset) {
            right = candidate;
            break;
        }
    }
    float leftOffset = texelFetch(StopBuffer, left * 2 + 1).x;
    float rightOffset = texelFetch(StopBuffer, right * 2 + 1).x;
    float progress = right == left ? 0.0 :
        (clampedOffset - leftOffset) / max(rightOffset - leftOffset, 0.0001);
    return mix(gradientStop(left), gradientStop(right), progress);
}

vec4 samplePaint(int paintIndex) {
    if (paintIndex < 0) return vec4(0.0);
    int base = paintIndex * 4;
    vec4 meta = texelFetch(PaintBuffer, base);
    int type = int(meta.x);
    vec4 color;
    if (type == PAINT_SOLID) {
        color = texelFetch(PaintBuffer, base + 1);
    } else if (type == PAINT_LINEAR_GRADIENT) {
        vec4 dimensions = texelFetch(PaintBuffer, base + 2);
        vec2 direction = dimensions.zw;
        vec2 center = dimensions.xy * 0.5;
        float projection = dot(localPosition - center, direction);
        float extent = dot(abs(direction), dimensions.xy * 0.5);
        float offset = extent <= 0.0 ? 0.0 : (projection / extent + 1.0) * 0.5;
        color = sampleStops(int(meta.y), int(meta.z), offset);
    } else if (type == PAINT_RADIAL_GRADIENT) {
        vec4 radial = texelFetch(PaintBuffer, base + 3);
        float offset = length(localPosition - radial.xy) / max(radial.z, 0.0001);
        color = sampleStops(int(meta.y), int(meta.z), offset);
    } else {
        color = vec4(0.0);
    }
    color.a *= meta.w;
    return color;
}

void main() {
    int base = recordIndex * 4;
    vec4 geometry = texelFetch(RecordBuffer, base);
    vec2 glyphFootprint = fwidth(glyphUv);
    float pixelSize = localPixelSize();

    vec4 clip = texelFetch(RecordBuffer, base + 3);
    if (clipPosition.x < clip.x || clipPosition.y < clip.y ||
        clipPosition.x > clip.z || clipPosition.y > clip.w) {
        discard;
    }

    if (geometry.x < 0.0) {
        int glyphPage = int(geometry.w);
        int glyphMode = int(geometry.z);
        vec2 uv = glyphUv;
        if (glyphMode != GLYPH_MSDF) {
            vec2 atlasSize = GlyphAtlasSize[glyphPage];
            if (atlasSize.x > 1.5 && atlasSize.y > 1.5) {
                vec4 uvRect = texelFetch(RecordBuffer, base + 1);
                vec2 halfTexel = 0.5 / atlasSize;
                vec2 low = min(uvRect.xy, uvRect.zw) + halfTexel;
                vec2 high = max(uvRect.xy, uvRect.zw) - halfTexel;
                uv = clamp(glyphUv, min(low, high), max(low, high));
            }
        }
        vec4 texel = sampleGlyphAtlas(glyphPage, uv);
        vec4 glyphColor = texelFetch(RecordBuffer, base + 2);
        vec3 rgb = glyphColor.rgb;
        float coverage;
        if (glyphMode == GLYPH_MSDF) {
            vec2 unitRange = vec2(GlyphDistanceRange[glyphPage]) / GlyphAtlasSize[glyphPage];
            vec2 screenTexSize = vec2(1.0) / max(glyphFootprint, vec2(1e-8));
            float pxRange = max(0.5 * dot(unitRange, screenTexSize), 2.0);
            float edgeSoftness = GlyphEdgeSoftness;
            float bias = min(geometry.y, max(0.5 - 0.5 * edgeSoftness / pxRange, 0.0));
            float sdPx = median3(texel.r, texel.g, texel.b) - 0.5 + bias;
            coverage = clamp(sdPx * pxRange / edgeSoftness + 0.5, 0.0, 1.0);
        } else {
            coverage = texel.a;
            if (glyphMode == GLYPH_COLOR) rgb *= texel.rgb;
        }
        float alpha = glyphColor.a * coverage;
        if (alpha <= 0.0) discard;
        fragColor = vec4(rgb, alpha);
        return;
    }

    vec2 size = geometry.xy;
    float radius = geometry.z;
    float borderWidth = geometry.w;
    vec4 effect = texelFetch(RecordBuffer, base + 1);
    int paintIndex = int(effect.x);
    int borderPaintIndex = effect.w > 0.5 ? -1 : int(effect.y);
    vec4 borderColor = borderPaintIndex >= 0
        ? samplePaint(borderPaintIndex)
        : texelFetch(RecordBuffer, base + 2);

    if (radius <= 0.0 && borderWidth <= 0.0 && effect.w <= 0.5) {
        vec4 fill = samplePaint(paintIndex);
        if (fill.a <= 0.0) discard;
        fragColor = fill;
        return;
    }

    if (effect.w > 1.5) {
        vec2 inner = max(size - borderWidth * 2.0, vec2(0.0));
        float innerRadius = max(radius - borderWidth, 0.0);
        vec2 point = localPosition - borderWidth;
        float boxCoverage = sdfCoverage(roundedRectDistance(point, inner, innerRadius), pixelSize);
        if (boxCoverage <= 0.0) discard;
        float spread = effect.z;
        vec2 hole = max(inner - spread * 2.0, vec2(0.0));
        vec2 offset = texelFetch(RecordBuffer, base + 2).xy;
        float holeDistance = roundedRectDistance(point - offset - spread, hole, max(innerRadius - spread, 0.0));
        vec4 shadowColor = samplePaint(paintIndex);
        shadowColor.a *= boxCoverage * (1.0 - softenedCoverage(holeDistance, effect.y, pixelSize));
        if (shadowColor.a <= 0.0) discard;
        fragColor = shadowColor;
        return;
    }

    float distance = roundedRectDistance(localPosition, size, radius);
    if (effect.w > 0.5) {
        float shadowCoverage = softenedCoverage(distance - effect.z, effect.y, pixelSize);
        vec4 shadowColor = samplePaint(paintIndex);
        shadowColor.a *= shadowCoverage;
        if (shadowColor.a <= 0.0) discard;
        fragColor = shadowColor;
        return;
    }
    float outerCoverage = sdfCoverage(distance, pixelSize);
    if (outerCoverage <= 0.0) discard;

    float borderCoverage = 0.0;
    if (borderWidth > 0.0) {
        vec2 innerSize = max(size - borderWidth * 2.0, vec2(0.0));
        float innerRadius = max(radius - borderWidth, 0.0);
        float innerDistance = roundedRectDistance(localPosition - borderWidth, innerSize, innerRadius);
        float innerCoverage = sdfCoverage(innerDistance, pixelSize);
        borderCoverage = clamp(outerCoverage - innerCoverage, 0.0, 1.0);
        if (effect.z > 0.0) {
            float position = outlinePosition(localPosition, size, radius, borderWidth * 0.5);
            borderCoverage *= dashCoverage(position, effect.z, -effect.w, pixelSize);
        }
    }

    vec4 fillColor = samplePaint(paintIndex);
    float fillAlpha = fillColor.a * outerCoverage;
    float borderAlpha = borderColor.a * borderCoverage;
    float outputAlpha = borderAlpha + fillAlpha * (1.0 - borderAlpha);
    if (outputAlpha <= 0.0) discard;
    vec3 outputColor = (
        borderColor.rgb * borderAlpha + fillColor.rgb * fillAlpha * (1.0 - borderAlpha)
    ) / outputAlpha;
    fragColor = vec4(outputColor, outputAlpha);
}
