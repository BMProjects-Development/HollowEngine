package ru.hollowhorizon.hollowengine.client.shadergraph

import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderNodeCategory.*
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderPinType.*

/** Arithmetic, vectors and patterns: the nodes that compute rather than read. */
internal object ShaderMathNodes {
    const val EXPRESSION = "hollowengine:math/expression"
    const val SHAPE = "hollowengine:procedural/shape"

    /** What a new expression node computes. */
    const val DEFAULT_EXPRESSION = "a * b"

    val all: List<ShaderNodeType> by lazy { math() + vector() + procedural() }

    private fun math() = listOf(
        binary("add", "arithmetic", 0f, 0f) { a, b -> "$a + $b" },
        binary("subtract", "arithmetic", 0f, 0f) { a, b -> "$a - $b" },
        binary("multiply", "arithmetic", 1f, 1f) { a, b -> "$a * $b" },
        binary("divide", "arithmetic", 1f, 1f) { a, b -> "$a / $b" },
        binary("power", "arithmetic", 1f, 2f) { a, b -> "pow($a, $b)" },
        unary("square_root", "arithmetic") { "sqrt($it)" },
        unary("one_minus", "arithmetic") { "1.0 - $it" },
        unary("negate", "arithmetic") { "-$it" },
        unary("absolute", "arithmetic") { "abs($it)" },

        binary("minimum", "range", 0f, 0f) { a, b -> "min($a, $b)" },
        binary("maximum", "range", 0f, 0f) { a, b -> "max($a, $b)" },
        binary("modulo", "range", 0f, 1f) { a, b -> "mod($a, $b)" },
        shaderNode("hollowengine:math/clamp", MATH) {
            group("range")
            val value = input("In", 0f)
            val min = input("Min", 0f)
            val max = input("Max", 1f)
            output("Out") { "clamp(${value.code}, ${min.code}, ${max.code})" }
        },
        unary("saturate", "range") { "clamp($it, 0.0, 1.0)" },
        unary("floor", "range") { "floor($it)" },
        unary("fraction", "range") { "fract($it)" },
        shaderNode("hollowengine:math/remap", MATH) {
            icon(graphIcon("gradient"))
            group("range")
            val value = input("In", 0.5f)
            val inMin = input("In Min", 0f)
            val inMax = input("In Max", 1f)
            val outMin = input("Out Min", 0f)
            val outMax = input("Out Max", 1f)
            output("Out") {
                "${outMin.code} + (${value.code} - ${inMin.code}) * (${outMax.code} - ${outMin.code}) / (${inMax.code} - ${inMin.code})"
            }
        },

        shaderNode("hollowengine:math/lerp", MATH) {
            icon(graphIcon("blend"))
            group("interpolation")
            val a = input("A", 0f)
            val b = input("B", 1f)
            val t = input("T", 0.5f)
            output("Out") { "mix(${a.code}, ${b.code}, ${t.code})" }
        },
        shaderNode("hollowengine:math/step", MATH) {
            icon(graphIcon("gradient"))
            group("interpolation")
            val edge = input("Edge", 0.5f)
            val value = input("In", 0f)
            output("Out") { "step(${edge.code}, ${value.code})" }
        },
        shaderNode("hollowengine:math/smoothstep", MATH) {
            icon(graphIcon("gradient"))
            group("interpolation")
            val from = input("Edge 1", 0f)
            val to = input("Edge 2", 1f)
            val value = input("In", 0.5f)
            output("Out") { "smoothstep(${from.code}, ${to.code}, ${value.code})" }
        },

        unary("sine", "trigonometry") { "sin($it)" },
        unary("cosine", "trigonometry") { "cos($it)" },

        expression(),
    )

    /**
     * GLSL typed on the node: every free name of the text is a pin, so `(sin(x) + 1) / 2` is a node
     * with an input `x`. A pin named like an engine input, such as `uv` or `time`, reads that input
     * while nothing is linked to it.
     */
    private fun expression() = shaderNode(EXPRESSION, MATH) {
        group("expression")
        expression("expression", DEFAULT_EXPRESSION)
        option("type", "auto", "float", "vec2", "vec3", "vec4")
        pins { node ->
            ShaderExpression.parse(node.options["expression"] ?: DEFAULT_EXPRESSION).variables.map { name ->
                ShaderPinSpec(name, ANY, listOf(0f), ShaderInput.forExpression(name))
            }
        }
        check { node ->
            ShaderExpression.parse(node.options["expression"] ?: DEFAULT_EXPRESSION).error?.let { error ->
                ShaderDiagnostic(
                    ShaderProblem.INVALID_EXPRESSION,
                    node.id,
                    error.detail,
                    reason = error.problem.name.lowercase()
                )
            }
        }
        // Left on auto, the type is read off the text: `length(v)` is a number, `v * 2` as wide as `v`.
        output("Out", typeOf = {
            when (node.options["type"]) {
                "float" -> FLOAT
                "vec2" -> VEC2
                "vec3" -> VEC3
                "vec4" -> VEC4
                else -> ShaderExpression.parse(node.options["expression"] ?: DEFAULT_EXPRESSION)
                    .width { name -> input(name)?.width }?.let { ShaderPinType.of(ShaderType.ofWidth(it)) } ?: FLOAT
            }
        }) {
            ShaderExpression.parse(option("expression")).glsl(::input) ?: shaderLiteral(outputType, listOf(0f))
        }
    }

    private fun vector() = listOf(
        shaderNode("hollowengine:vector/length", VECTOR) {
            group("measure")
            val value = input("In", 0f)
            output("Out", FLOAT) { "length(${value.code})" }
        },
        shaderNode("hollowengine:vector/distance", VECTOR) {
            group("measure")
            val a = input("A", 0f)
            val b = input("B", 0f)
            output("Out", FLOAT) { "distance(${a.code}, ${b.code})" }
        },
        shaderNode("hollowengine:vector/dot", VECTOR) {
            group("measure")
            val a = input("A", 0f)
            val b = input("B", 0f)
            output("Out", FLOAT) { "dot(${a.code}, ${b.code})" }
        },
        shaderNode("hollowengine:vector/cross", VECTOR) {
            group("measure")
            val a = input("A", 1f, 0f, 0f, type = VEC3)
            val b = input("B", 0f, 1f, 0f, type = VEC3)
            output("Out", VEC3) { "cross(${a.code}, ${b.code})" }
        },
        shaderNode("hollowengine:vector/normalize", VECTOR) {
            group("measure")
            val value = input("In", 0f, 1f, 0f)
            output("Out") { "normalize(${value.code})" }
        },
        shaderNode("hollowengine:vector/split", VECTOR) {
            icon(graphIcon("split"))
            group("channels")
            noPreview()
            val value = input("In", 0f, 0f, 0f, 0f, type = VEC4)
            output("R", FLOAT) { "${value.code}.x" }
            output("G", FLOAT) { "${value.code}.y" }
            output("B", FLOAT) { "${value.code}.z" }
            output("A", FLOAT) { "${value.code}.w" }
        },
        shaderNode("hollowengine:vector/combine", VECTOR) {
            icon(graphIcon("split"))
            group("channels")
            val r = input("R", 0f, type = FLOAT)
            val g = input("G", 0f, type = FLOAT)
            val b = input("B", 0f, type = FLOAT)
            val a = input("A", 1f, type = FLOAT)
            output("RGBA", VEC4) { "vec4(${r.code}, ${g.code}, ${b.code}, ${a.code})" }
            output("RGB", VEC3) { "${output("RGBA")}.rgb" }
            output("RG", VEC2) { "${output("RGBA")}.rg" }
        },
        shaderNode("hollowengine:vector/swizzle", VECTOR) {
            icon(graphIcon("split"))
            group("channels")
            text("mask", default = "xyz")
            val value = input("In", 0f, 0f, 0f, 0f, type = VEC4)
            output("Out", { ShaderPinType.of(ShaderType.ofWidth(swizzleMask(node.options["mask"] ?: "xyz").length)) }) {
                "${value.code}.${swizzleMask(option("mask"))}"
            }
        },
    )

    private fun procedural() = listOf(
        noise("value_noise", ShaderLibrary.VALUE_NOISE, ShaderLibrary.VALUE_NOISE3, "sg_value_noise"),
        noise("gradient_noise", ShaderLibrary.GRADIENT_NOISE, ShaderLibrary.GRADIENT_NOISE3, "sg_gradient_noise"),
        shaderNode("hollowengine:procedural/fbm", PROCEDURAL) {
            group("noise")
            uses(ShaderLibrary.FBM, ShaderLibrary.FBM3)
            val uv = input("UV", fallback = ShaderInput.UV)
            val scale = input("Scale", 4f, type = FLOAT)
            val octaves = input("Octaves", 4f, type = FLOAT)
            val roughness = input("Roughness", 0.5f, type = FLOAT)
            output("Out", FLOAT) { noiseCall("sg_fbm", dynamic, uv.code, scale.code, octaves.code, roughness.code) }
        },
        shaderNode("hollowengine:procedural/voronoi", PROCEDURAL) {
            group("noise")
            uses(ShaderLibrary.VORONOI, ShaderLibrary.VORONOI3)
            val uv = input("UV", fallback = ShaderInput.UV)
            val scale = input("Scale", 5f, type = FLOAT)
            val jitter = input("Jitter", 1f, type = FLOAT)
            output("Distance", FLOAT) { noiseCall("sg_voronoi", dynamic, uv.code, scale.code, jitter.code) + ".x" }
            output("Cell", FLOAT) { noiseCall("sg_voronoi", dynamic, uv.code, scale.code, jitter.code) + ".y" }
        },
        shape(),
        shaderNode("hollowengine:procedural/fresnel", PROCEDURAL) {
            icon(graphIcon("light"))
            group("lighting")
            val normal = input("Normal", type = VEC3, fallback = ShaderInput.NORMAL)
            val view = input("View", type = VEC3, fallback = ShaderInput.VIEW_DIRECTION)
            val power = input("Power", 5f, type = FLOAT)
            output("Out", FLOAT) {
                "pow(1.0 - clamp(abs(dot(normalize(${normal.code}), normalize(${view.code}))), 0.0, 1.0), ${power.code})"
            }
        },
    )

    /**
     * A shape centered on the surface: a circle for a particle, a long rectangle for a beam. Width and
     * height are fractions of the surface; the inputs a shape does not use are not there.
     */
    private fun shape() = shaderNode(SHAPE, PROCEDURAL) {
        icon(graphIcon("mask"))
        group("shape")
        uses(ShaderLibrary.SHAPES)
        option("shape", "ellipse", "rectangle", "polygon", "ring")
        fun shapeIs(vararg names: String): (ShaderGraphNode) -> Boolean =
            { node -> (node.options["shape"] ?: "ellipse") in names }

        val uv = input("UV", type = VEC2, fallback = ShaderInput.UV)
        val width = input("Width", 1f, type = FLOAT)
        val height = input("Height", 1f, type = FLOAT)
        val roundness = input("Roundness", 0f, type = FLOAT, shownFor = shapeIs("rectangle"))
        val sides = input("Sides", 6f, type = FLOAT, shownFor = shapeIs("polygon"))
        val thickness = input("Thickness", 0.1f, type = FLOAT, shownFor = shapeIs("ring"))
        val softness = input("Softness", 0.02f, type = FLOAT)
        output("Distance", FLOAT) {
            val p = "(${uv.code} * 2.0 - 1.0)"
            val size = "vec2(${width.code}, ${height.code})"
            when (option("shape")) {
                "rectangle" -> "sg_shape_rectangle($p, $size, ${roundness.code})"
                "polygon" -> "sg_shape_polygon($p, $size, ${sides.code})"
                "ring" -> "sg_shape_ring($p, $size, ${thickness.code})"
                else -> "sg_shape_ellipse($p, $size)"
            }
        }
        output("Mask", FLOAT) { "sg_shape_mask(${output("Distance")}, ${softness.code})" }
    }

    private fun binary(name: String, group: String, a: Float, b: Float, expression: (String, String) -> String) =
        shaderNode("hollowengine:math/$name", MATH) {
            group(group)
            val left = input("A", a)
            val right = input("B", b)
            output("Out") { expression(left.code, right.code) }
        }

    private fun unary(name: String, group: String, expression: (String) -> String) =
        shaderNode("hollowengine:math/$name", MATH) {
            group(group)
            val value = input("In", 0f)
            output("Out") { expression("(${value.code})") }
        }

    /** A noise over a UV, or over a position in 3D, whichever its coordinates are. */
    private fun noise(name: String, flat: ShaderLibrary, solid: ShaderLibrary, function: String) =
        shaderNode("hollowengine:procedural/$name", PROCEDURAL) {
            group("noise")
            uses(flat, solid)
            val uv = input("UV", fallback = ShaderInput.UV)
            val scale = input("Scale", 8f, type = FLOAT)
            output("Out", FLOAT) { noiseCall(function, dynamic, uv.code, scale.code) }
        }

    /**
     * A call of a noise [function] on [coordinates] of [type]: its 3D version, suffixed `3`, for a
     * position, the 2D one for a UV; a number is read as a UV along x.
     */
    private fun noiseCall(
        function: String,
        type: ShaderType,
        coordinates: String,
        scale: String,
        vararg rest: String,
    ): String {
        val tail = rest.joinToString("") { ", $it" }
        return when (type.width) {
            1 -> "$function(vec2($coordinates, 0.0) * $scale$tail)"
            2 -> "$function($coordinates * $scale$tail)"
            3 -> "${function}3($coordinates * $scale$tail)"
            else -> "${function}3(($coordinates).xyz * $scale$tail)"
        }
    }
}

/** A swizzle mask as GLSL takes it: one to four of `xyzw`, in `rgba` too; anything else reads `x`. */
internal fun swizzleMask(mask: String?): String {
    val normalized = mask.orEmpty().lowercase().map { char ->
        when (char) {
            'r' -> 'x'
            'g' -> 'y'
            'b' -> 'z'
            'a' -> 'w'
            else -> char
        }
    }.joinToString("")
    return normalized.takeIf { it.length in 1..4 && it.all { char -> char in "xyzw" } } ?: "x"
}
