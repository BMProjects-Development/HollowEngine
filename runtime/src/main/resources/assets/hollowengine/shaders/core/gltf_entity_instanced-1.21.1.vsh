#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec3 Normal;

in float _he_Slot;
in ivec2 InstanceUV1;
in ivec2 InstanceUV2;

uniform samplerBuffer _he_Transforms;
uniform int _he_SlotCount;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 ProjMat;
uniform int FogShape;
uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec4 normal;

mat3 slot_normal(mat3 slot) {
    float det = determinant(slot);
    if (abs(det) < 1e-8) return mat3(1.0);
    return transpose(inverse(slot)) * pow(abs(det), 1.0 / 3.0);
}

float instanced_fog_distance(vec3 viewPos, int fogShape) {
    if (fogShape == 0) {
        return length(viewPos);
    }
    return max(length(viewPos.xz), abs(viewPos.y));
}

void main() {
    int instance = gl_InstanceID * (7 + 4 * _he_SlotCount);
    int at = instance + 7 + int(_he_Slot + 0.5) * 4;
    mat4 slot = mat4(
        texelFetch(_he_Transforms, at),
        texelFetch(_he_Transforms, at + 1),
        texelFetch(_he_Transforms, at + 2),
        texelFetch(_he_Transforms, at + 3)
    );
    mat4 modelView = mat4(
        texelFetch(_he_Transforms, instance),
        texelFetch(_he_Transforms, instance + 1),
        texelFetch(_he_Transforms, instance + 2),
        texelFetch(_he_Transforms, instance + 3)
    ) * slot;
    mat3 normalMat = mat3(
        texelFetch(_he_Transforms, instance + 4).xyz,
        texelFetch(_he_Transforms, instance + 5).xyz,
        texelFetch(_he_Transforms, instance + 6).xyz
    ) * slot_normal(mat3(slot));

    vec4 viewPos = modelView * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    vertexDistance = instanced_fog_distance(viewPos.xyz, FogShape);
    vec3 fixNormal = normalize(normalMat * Normal);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, fixNormal, Color);
    lightMapColor = texelFetch(Sampler2, InstanceUV2 / 16, 0);
    overlayColor = texelFetch(Sampler1, InstanceUV1, 0);
    texCoord0 = UV0;
    normal = vec4(fixNormal, 0.0);
}
