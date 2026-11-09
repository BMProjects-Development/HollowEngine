#version 330 core

uniform samplerBuffer SegmentBuffer;
uniform isamplerBuffer SegmentIndexBuffer;
uniform isamplerBuffer TileBuffer;
uniform samplerBuffer PaintBuffer;
uniform samplerBuffer StopBuffer;
uniform samplerBuffer ClipBuffer;

in vec2 localPosition;
in vec2 clipPosition;
flat in int tileIndex;

layout(location = 0) out vec4 fragColor;

const int TILE_FULL_COVERAGE = 1;
const int TILE_STYLE_STROKE = 1;
const int PAINT_SOLID = 0;
const int PAINT_LINEAR_GRADIENT = 1;
const int PAINT_RADIAL_GRADIENT = 2;

int windingAt(vec2 point, int segmentStart, int segmentCount) {
    int winding = 0;
    for (int index = 0; index < segmentCount; index++) {
        int segmentIndex = texelFetch(SegmentIndexBuffer, segmentStart + index).x;
        vec4 segment = texelFetch(SegmentBuffer, segmentIndex);
        vec2 first = segment.xy;
        vec2 second = segment.zw;
        bool crosses = (first.y <= point.y && second.y > point.y) ||
            (second.y <= point.y && first.y > point.y);
        if (!crosses) continue;
        float progress = (point.y - first.y) / (second.y - first.y);
        float crossingX = first.x + (second.x - first.x) * progress;
        if (crossingX <= point.x) winding += second.y > first.y ? 1 : -1;
    }
    return winding;
}

float segmentDistance(vec2 point, vec4 segment) {
    vec2 delta = segment.zw - segment.xy;
    float lengthSquared = dot(delta, delta);
    float progress = lengthSquared <= 0.000001 ? 0.0 :
        clamp(dot(point - segment.xy, delta) / lengthSquared, 0.0, 1.0);
    return length(point - (segment.xy + delta * progress));
}

float minimumSegmentDistance(vec2 point, ivec4 tile) {
    float distance = 1e20;
    for (int index = 0; index < tile.y; index++) {
        int segmentIndex = texelFetch(SegmentIndexBuffer, tile.x + index).x;
        distance = min(distance, segmentDistance(point, texelFetch(SegmentBuffer, segmentIndex)));
    }
    return distance;
}

float softenedCoverage(float edge, float blurRadius, float pixelSize) {
    float aa = pixelSize;
    if (blurRadius <= 0.0001) return clamp(0.5 - edge / aa, 0.0, 1.0);
    float extent = max(blurRadius * 3.0, aa);
    return 1.0 - smoothstep(-extent, extent, edge);
}

float strokeCoverage(vec2 point, ivec4 tile, float radius, float spreadRadius, float blurRadius, float pixelSize) {
    float distance = minimumSegmentDistance(point, tile);
    float edge = distance - radius - spreadRadius;
    return softenedCoverage(edge, blurRadius, pixelSize);
}

float pathCoverage(ivec4 tile, ivec4 style, vec2 pixelX, vec2 pixelY) {
    float pixelSize = max(sqrt(0.5 * (dot(pixelX, pixelX) + dot(pixelY, pixelY))), 0.0001);
    float spreadRadius = intBitsToFloat(style.z);
    float blurRadius = intBitsToFloat(style.w);
    if (style.x == TILE_STYLE_STROKE) {
        return strokeCoverage(localPosition, tile, intBitsToFloat(style.y), spreadRadius, blurRadius, pixelSize);
    }
    if ((tile.w & TILE_FULL_COVERAGE) != 0) return 1.0;
    if (blurRadius > 0.0001 || abs(spreadRadius) > 0.0001) {
        float distance = minimumSegmentDistance(localPosition, tile);
        float edge = windingAt(localPosition, tile.x, tile.y) == 0 ? distance : -distance;
        return softenedCoverage(edge - spreadRadius, blurRadius, pixelSize);
    }
    vec2 horizontal = pixelX * 0.25;
    vec2 vertical = pixelY * 0.25;
    float coverage = 0.0;
    coverage += windingAt(localPosition - horizontal - vertical, tile.x, tile.y) != 0 ? 0.25 : 0.0;
    coverage += windingAt(localPosition + horizontal - vertical, tile.x, tile.y) != 0 ? 0.25 : 0.0;
    coverage += windingAt(localPosition - horizontal + vertical, tile.x, tile.y) != 0 ? 0.25 : 0.0;
    coverage += windingAt(localPosition + horizontal + vertical, tile.x, tile.y) != 0 ? 0.25 : 0.0;
    return coverage;
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
    int tileOffset = tileIndex * 2;
    ivec4 tile = texelFetch(TileBuffer, tileOffset);
    ivec4 style = texelFetch(TileBuffer, tileOffset + 1);
    vec2 pixelX = dFdx(localPosition);
    vec2 pixelY = dFdy(localPosition);
    if (style.x < 0) discard;
    vec4 clip = texelFetch(ClipBuffer, tile.z);
    if (clipPosition.x < clip.x || clipPosition.y < clip.y ||
        clipPosition.x > clip.z || clipPosition.y > clip.w) {
        discard;
    }
    float coverage = pathCoverage(tile, style, pixelX, pixelY);
    if (coverage <= 0.0) discard;
    fragColor = samplePaint(tile.z);
    fragColor.a *= coverage;
}
