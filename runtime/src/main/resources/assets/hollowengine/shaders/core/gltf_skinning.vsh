#version 330 core

layout (location = 0) in ivec4 joint;
layout (location = 1) in vec4 weight;
layout (location = 2) in vec3 position;
layout (location = 3) in vec3 normal;
layout(location = 4) in vec4 tangent;
layout(location = 5) in ivec2 additionalWeightRange;

uniform samplerBuffer jointMatrices;
uniform samplerBuffer additionalWeights;
uniform int hasAdditionalWeights;

uniform samplerBuffer morphDeltasPosition;
uniform samplerBuffer morphDeltasNormal;
uniform samplerBuffer morphDeltasTangent;
uniform float morphWeights[64];
uniform int activeMorphCount;
uniform int vertexCount;

out vec3 outPosition;
out vec3 outNormal;
out vec4 outTangent;

void main() {
    vec3 morphedPos = position;
    vec3 morphedNor = normal;
    vec3 morphedTan = tangent.xyz;

    // gl_VertexID - это индекс текущей вершины (от 0 до vertexCount-1)
    int vertId = gl_VertexID;

    for (int i = 0; i < activeMorphCount; i++) {
        float w = morphWeights[i];

        // Пропускаем нулевые веса
        if (abs(w) > 0.0001) {
            // Индекс в TBO = (НомерТаргета * КолВоВершин) + ИндексВершины
            int bufferIndex = (i * vertexCount) + vertId;

            // Читаем дельты (TBO возвращает vec4, берем xyz)
            vec3 dPos = texelFetch(morphDeltasPosition, bufferIndex).xyz;
            vec3 dNor = texelFetch(morphDeltasNormal, bufferIndex).xyz;
            vec3 dTan = texelFetch(morphDeltasTangent, bufferIndex).xyz;

            morphedPos += dPos * w;
            morphedNor += dNor * w;
            morphedTan += dTan * w;
        }
    }

    int jx = joint.x * 4;
    int jy = joint.y * 4;
    int jz = joint.z * 4;
    int jw = joint.w * 4;

    mat4 skinMatrix = weight.x * mat4(
    texelFetch(jointMatrices, jx),
    texelFetch(jointMatrices, jx + 1),
    texelFetch(jointMatrices, jx + 2),
    texelFetch(jointMatrices, jx + 3)
    ) + weight.y * mat4(
    texelFetch(jointMatrices, jy),
    texelFetch(jointMatrices, jy + 1),
    texelFetch(jointMatrices, jy + 2),
    texelFetch(jointMatrices, jy + 3)
    ) + weight.z * mat4(
    texelFetch(jointMatrices, jz),
    texelFetch(jointMatrices, jz + 1),
    texelFetch(jointMatrices, jz + 2),
    texelFetch(jointMatrices, jz + 3)
    ) + weight.w * mat4(
    texelFetch(jointMatrices, jw),
    texelFetch(jointMatrices, jw + 1),
    texelFetch(jointMatrices, jw + 2),
    texelFetch(jointMatrices, jw + 3)
    );

    if (hasAdditionalWeights != 0) {
        for (int i = 0; i < additionalWeightRange.y; i++) {
            vec2 influence = texelFetch(additionalWeights, additionalWeightRange.x + i).rg;
            int jointIndex = int(influence.x) * 4;
            skinMatrix += influence.y * mat4(
                texelFetch(jointMatrices, jointIndex),
                texelFetch(jointMatrices, jointIndex + 1),
                texelFetch(jointMatrices, jointIndex + 2),
                texelFetch(jointMatrices, jointIndex + 3)
            );
        }
    }

    vec4 skinnedPos = skinMatrix * vec4(morphedPos, 1.0);
    outPosition = skinnedPos.xyz / skinnedPos.w;

    mat3 linearMatrix = mat3(skinMatrix);
    mat3 normalMatrix = transpose(inverse(linearMatrix));

    vec3 skinnedNormal = normalize(normalMatrix * morphedNor);

    vec3 skinnedTangent = linearMatrix * morphedTan;
    skinnedTangent = normalize(
        skinnedTangent -
        skinnedNormal * dot(skinnedNormal, skinnedTangent)
    );

    outNormal = skinnedNormal;
    outTangent = vec4(skinnedTangent, tangent.w);
}
