#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;
uniform sampler2D SceneDepth;

uniform mat4 InvViewProjMat;
uniform mat4 DecalInverse;
uniform vec3 DecalUp;
uniform vec4 DecalColor;
uniform float AngleFade;
uniform vec2 LightUV;
uniform vec2 ScreenSize;
uniform vec4 Viewport;

uniform float Multiply;

uniform float Overlay;
uniform vec4 OverlayColor;

uniform vec4 CastFrom;

uniform sampler2D BlockDepth;
uniform float Blocks;
uniform float Carrier;
uniform vec3 CarrierMin;
uniform vec3 CarrierMax;

out vec4 fragColor;

vec3 pointAt(vec2 pixel, float depth) {
    vec2 screen = (pixel - Viewport.xy) / Viewport.zw;
    vec4 point = InvViewProjMat * vec4(screen * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return point.xyz / point.w;
}

vec3 surfaceAt(vec2 pixel) {
    return pointAt(pixel, texture(SceneDepth, clamp(pixel, vec2(0.5), ScreenSize - 0.5) / ScreenSize).r);
}

bool onEntity(vec2 pixel, vec3 surface) {
    if (Blocks < 0.5) return false;
    vec3 block = pointAt(pixel, texture(BlockDepth, clamp(pixel, vec2(0.5), ScreenSize - 0.5) / ScreenSize).r);
    float distance = length(surface);
    if (length(block) - distance <= 0.02 + distance * 0.002) return false;
    bool carried = Carrier > 0.5 && all(greaterThan(surface, CarrierMin - 0.05)) && all(lessThan(surface, CarrierMax + 0.05));
    return !carried;
}

vec4 cubeAt(vec3 point) {
    vec4 local = DecalInverse * vec4(point, 1.0);
    return vec4(local.xyz / local.w, local.w);
}

vec2 pictureAt(vec3 cube) {
    return vec2(cube.x * 0.5 + 0.5, 0.5 - cube.y * 0.5);
}

void main() {
    vec2 pixel = gl_FragCoord.xy;
    vec3 surface = surfaceAt(pixel);

    vec3 right = surfaceAt(pixel + vec2(1.0, 0.0)) - surface;
    vec3 left = surface - surfaceAt(pixel - vec2(1.0, 0.0));
    vec3 up = surfaceAt(pixel + vec2(0.0, 1.0)) - surface;
    vec3 down = surface - surfaceAt(pixel - vec2(0.0, 1.0));
    vec3 across = dot(right, right) < dot(left, left) ? right : left;
    vec3 along = dot(up, up) < dot(down, down) ? up : down;

    vec4 cube = cubeAt(surface);
    vec2 uv = pictureAt(cube.xyz);
    vec2 uvAcross = pictureAt(cubeAt(surface + across).xyz) - uv;
    vec2 uvAlong = pictureAt(cubeAt(surface + along).xyz) - uv;

    if (cube.w <= 0.0 || any(greaterThan(abs(cube.xyz), vec3(1.0)))) discard;
    if (onEntity(pixel, surface)) discard;

    vec3 toSource = CastFrom.w > 0.5 ? normalize(CastFrom.xyz - surface) : DecalUp;
    vec3 normal = normalize(cross(across, along));
    if (dot(normal, surface) > 0.0) normal = -normal;
    float facing = dot(normal, toSource);
    float fade = smoothstep(AngleFade, min(AngleFade + 0.15, 1.0), facing);

    if (Overlay > 0.5) {
        float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
        float border = 1.0 - smoothstep(0.0, 0.025, edge);
        float top = uv.y < 0.1 ? 0.6 : 0.0;
        float strength = mix(0.3, 1.0, fade);
        fragColor = vec4(OverlayColor.rgb, OverlayColor.a * strength * (0.45 + 0.55 * max(border, top)));
        return;
    }
    if (fade <= 0.0) discard;

    vec4 color = textureGrad(Sampler0, uv, uvAcross, uvAlong) * DecalColor;
    if (LightUV.x >= 0.0) color.rgb *= texture(Sampler2, clamp(LightUV / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
    color.a *= fade;
    if (color.a <= 0.004) discard;
    fragColor = Multiply > 0.5 ? vec4(mix(vec3(1.0), color.rgb, color.a), 1.0) : color;
}
