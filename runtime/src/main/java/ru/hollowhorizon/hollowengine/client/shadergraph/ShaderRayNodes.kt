package ru.hollowhorizon.hollowengine.client.shadergraph

import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderNodeCategory.RAY
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderPinType.*

/**
 * Shapes that are not there, traced along the ray from the eye: what lets a graph draw a hole, a
 * dome of light or a decal on the ground where the scene has none. Points are relative to the eye,
 * as the position is; Position is where the ray stops, the scene for a post effect.
 */
internal object ShaderRayNodes {
    val all: List<ShaderNodeType> by lazy { listOf(plane(), sphere(), screen()) }

    /**
     * Where the ray crosses a plane. Gap is how much farther the scene is than the plane: below zero
     * something stands in front of it.
     */
    private fun plane() = shaderNode("hollowengine:ray/plane", RAY) {
        group("shapes")
        uses(ShaderLibrary.RAYS)
        val point = input("Point", 0f, 0f, 0f, type = VEC3)
        val normal = input("Normal", 0f, 1f, 0f, type = VEC3)
        val position = input("Position", type = VEC3, fallback = ShaderInput.POSITION)
        output("Distance", FLOAT) { "sg_ray_plane(${position.code}, ${point.code}, normalize(${normal.code}))" }
        output("Point", VEC3) { "normalize(${position.code}) * max(${output("Distance")}, 0.0)" }
        output("Gap", FLOAT) {
            "${output("Distance")} < 0.0 ? -1000.0 : length(${position.code}) - ${output("Distance")}"
        }
    }

    /**
     * Where the ray enters and leaves a sphere. Inside is how far it runs within the sphere before it
     * stops, which is how much of a glowing or foggy volume the eye looks through.
     */
    private fun sphere() = shaderNode("hollowengine:ray/sphere", RAY) {
        group("shapes")
        uses(ShaderLibrary.RAYS)
        val center = input("Center", 0f, 0f, 0f, type = VEC3)
        val radius = input("Radius", 1f, type = FLOAT)
        val position = input("Position", type = VEC3, fallback = ShaderInput.POSITION)
        fun ShaderEmitContext.trace(component: String) =
            "sg_ray_sphere(${position.code}, ${center.code}, ${radius.code}).$component"
        output("Inside", FLOAT) { trace("z") }
        output("Hit", FLOAT) { trace("w") }
        output("Enter", FLOAT) { trace("x") }
        output("Leave", FLOAT) { trace("y") }
        output("Entry", VEC3) { "normalize(${position.code}) * max(${output("Enter")}, 0.0)" }
        output("Exit", VEC3) { "normalize(${position.code}) * max(${output("Leave")}, 0.0)" }
    }

    /** Where a point lands on the screen; Ahead is 0 for one behind the eye, where UV means nothing. */
    private fun screen() = shaderNode("hollowengine:ray/screen_position", RAY) {
        group("screen")
        icon(graphIcon("coordinates"))
        reads(ShaderInput.SCREEN_PROJECTION)
        val point = input("Point", 0f, 0f, -4f, type = VEC3)
        output("UV", VEC2) { "${ShaderInput.SCREEN_PROJECTION.glsl}(${point.code}).xy" }
        output("Ahead", FLOAT) { "step(0.0, ${ShaderInput.SCREEN_PROJECTION.glsl}(${point.code}).z)" }
    }
}
