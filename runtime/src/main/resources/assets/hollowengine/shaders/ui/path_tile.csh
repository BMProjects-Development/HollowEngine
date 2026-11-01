#version 430 core

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

layout(std430, binding = 0) readonly buffer SegmentBuffer {
    vec4 segments[];
};

layout(std430, binding = 1) writeonly buffer SegmentIndexBuffer {
    int segmentIndices[];
};

layout(std430, binding = 2) writeonly buffer TileBuffer {
    ivec4 tiles[];
};

layout(std430, binding = 5) readonly buffer InputBuffer {
    vec4 inputs[];
};

layout(std430, binding = 7) writeonly buffer VertexBuffer {
    float vertices[];
};

layout(std430, binding = 6) buffer IndirectBuffer {
    uint vertexCount;
    uint instanceCount;
    uint firstVertex;
    uint baseInstance;
    uint segmentIndexCount;
};

uniform int CandidateCount;
uniform int CandidateOffset;

const int PATH_STRIDE = 8;
const int VERTEX_STRIDE = 6;
const uint VERTICES_PER_TILE = 6u;
const int TILE_FULL_COVERAGE = 1;
const int TILE_STYLE_FILL = 0;
const int TILE_STYLE_STROKE = 1;

bool rowOverlaps(vec4 segment, float rowMinY, float rowMaxY, float margin) {
    return max(segment.y, segment.w) + margin >= rowMinY &&
        min(segment.y, segment.w) - margin <= rowMaxY;
}

bool tileOverlaps(vec4 segment, vec2 tileMin, vec2 tileMax, float margin) {
    return max(segment.x, segment.z) + margin >= tileMin.x &&
        min(segment.x, segment.z) - margin <= tileMax.x &&
        rowOverlaps(segment, tileMin.y, tileMax.y, margin);
}

int crossingDelta(vec4 segment, vec2 point) {
    vec2 first = segment.xy;
    vec2 second = segment.zw;
    bool crosses = (first.y <= point.y && second.y > point.y) ||
        (second.y <= point.y && first.y > point.y);
    if (!crosses) return 0;
    float progress = (point.y - first.y) / (second.y - first.y);
    float crossingX = first.x + (second.x - first.x) * progress;
    if (crossingX > point.x) return 0;
    return second.y > first.y ? 1 : -1;
}

vec3 transformPoint(int pathBase, vec2 point) {
    vec4 localPoint = vec4(point, 0.0, 1.0);
    vec4 worldPoint = vec4(
        dot(inputs[pathBase + 3], localPoint),
        dot(inputs[pathBase + 4], localPoint),
        dot(inputs[pathBase + 5], localPoint),
        dot(inputs[pathBase + 6], localPoint)
    );
    if (worldPoint.w != 0.0 && worldPoint.w != 1.0) worldPoint.xyz /= worldPoint.w;
    return worldPoint.xyz;
}

void writeVertex(uint vertexIndex, int pathBase, vec2 localPoint, uint tileIndex) {
    uint offset = vertexIndex * uint(VERTEX_STRIDE);
    vec3 position = transformPoint(pathBase, localPoint);
    vertices[offset] = position.x;
    vertices[offset + 1] = position.y;
    vertices[offset + 2] = position.z;
    vertices[offset + 3] = localPoint.x;
    vertices[offset + 4] = localPoint.y;
    vertices[offset + 5] = float(tileIndex);
}

void discardTile(uint vertexStart, uint tileOffset) {
    tiles[tileOffset] = ivec4(0, 0, 0, 0);
    tiles[tileOffset + 1u] = ivec4(0, 0, 0, 0);
    for (uint index = 0u; index < VERTICES_PER_TILE; index++) {
        uint offset = (vertexStart + index) * uint(VERTEX_STRIDE);
        vertices[offset] = 0.0;
        vertices[offset + 1] = 0.0;
        vertices[offset + 2] = 0.0;
        vertices[offset + 3] = 0.0;
        vertices[offset + 4] = 0.0;
        vertices[offset + 5] = 0.0;
    }
}

void main() {
    uint candidateIndex = gl_GlobalInvocationID.x;
    if (candidateIndex >= uint(CandidateCount)) return;

    uint tileIndex = candidateIndex;
    uint vertexStart = tileIndex * VERTICES_PER_TILE;
    uint tileOffset = tileIndex * 2u;

    ivec4 candidate = ivec4(inputs[CandidateOffset + int(candidateIndex)]);
    int pathBase = candidate.x * PATH_STRIDE;
    vec4 pathMeta = inputs[pathBase];
    vec4 pathSize = inputs[pathBase + 1];
    int segmentStart = int(pathMeta.x);
    int pathSegmentCount = int(pathMeta.y);
    int paintIndex = int(pathMeta.z);
    float tileSize = pathMeta.w;
    float margin = pathSize.z;
    float strokeRadius = inputs[pathBase + 2].w;
    vec2 effect = inputs[pathBase + 7].xy;
    bool stroke = strokeRadius > 0.0;
    vec2 tileMin = vec2(candidate.yz) * tileSize;
    vec2 tileMax = (vec2(candidate.yz) + 1.0) * tileSize;
    if (any(lessThanEqual(tileMax, tileMin))) {
        discardTile(vertexStart, tileOffset);
        return;
    }

    vec2 center = (tileMin + tileMax) * 0.5;
    int winding = 0;
    int rowSegmentCount = 0;
    bool boundary = false;
    for (int localIndex = 0; localIndex < pathSegmentCount; localIndex++) {
        vec4 segment = segments[segmentStart + localIndex];
        bool near = tileOverlaps(segment, tileMin, tileMax, margin);
        if (stroke ? near : rowOverlaps(segment, tileMin.y, tileMax.y, margin)) rowSegmentCount++;
        if (near) boundary = true;
        winding += crossingDelta(segment, center);
    }
    if (!boundary && (stroke || winding == 0)) {
        discardTile(vertexStart, tileOffset);
        return;
    }

    uint outputSegmentStart = 0;
    if (boundary) {
        outputSegmentStart = atomicAdd(segmentIndexCount, uint(rowSegmentCount));
        uint outputIndex = outputSegmentStart;
        for (int localIndex = 0; localIndex < pathSegmentCount; localIndex++) {
            vec4 segment = segments[segmentStart + localIndex];
            bool kept = stroke ? tileOverlaps(segment, tileMin, tileMax, margin) :
                rowOverlaps(segment, tileMin.y, tileMax.y, margin);
            if (kept) segmentIndices[outputIndex++] = segmentStart + localIndex;
        }
    }

    tiles[tileOffset] = ivec4(
        int(outputSegmentStart),
        boundary ? rowSegmentCount : 0,
        paintIndex,
        boundary ? 0 : TILE_FULL_COVERAGE
    );
    tiles[tileOffset + 1u] = ivec4(
        stroke ? TILE_STYLE_STROKE : TILE_STYLE_FILL,
        floatBitsToInt(strokeRadius),
        floatBitsToInt(effect.y),
        floatBitsToInt(effect.x)
    );
    writeVertex(vertexStart, pathBase, tileMin, tileIndex);
    writeVertex(vertexStart + 1u, pathBase, vec2(tileMin.x, tileMax.y), tileIndex);
    writeVertex(vertexStart + 2u, pathBase, tileMax, tileIndex);
    writeVertex(vertexStart + 3u, pathBase, tileMin, tileIndex);
    writeVertex(vertexStart + 4u, pathBase, tileMax, tileIndex);
    writeVertex(vertexStart + 5u, pathBase, vec2(tileMax.x, tileMin.y), tileIndex);
}
