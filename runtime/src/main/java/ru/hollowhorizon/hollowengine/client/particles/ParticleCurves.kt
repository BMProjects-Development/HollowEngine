package ru.hollowhorizon.hollowengine.client.particles

import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockContext
import ru.hollowhorizon.hollowengine.client.models.bedrock.Variables
import ru.hollowhorizon.hollowengine.client.particles.file.BedrockParticleFile
import ru.hollowhorizon.hollowengine.client.utils.math.bezier
import ru.hollowhorizon.hollowengine.client.utils.math.catmullRom
import ru.hollowhorizon.hollowengine.client.utils.math.lerp

class CurveVariables(
    private val context: () -> BedrockContext,
    private val curves: Map<String, BedrockParticleFile.Curve>,
) : Variables {
    private var frame = 0

    private val variables = mutableMapOf<String, Variable?>()

    fun update() {
        frame++
    }

    override fun getOrNull(name: String): Variables.Variable? = variables.getOrPut(name) {
        curves["variable.$name"]?.let { Variable(it) }
    }

    override fun getOrPut(name: String, initialValue: Float): Variables.Variable =
        getOrNull(name) ?: throw UnsupportedOperationException("$this does not support unknown variables.svg")

    private inner class Variable(val curve: BedrockParticleFile.Curve) : Variables.Variable {
        private var cachedFrame = -1
        private var cachedValue: Float = 0f

        override fun get(): Float {
            if (cachedFrame == frame) {
                return cachedValue
            }
            val value = curve.eval(context())
            cachedFrame = frame
            cachedValue = value
            return value
        }

        override fun set(value: Float) = Unit
    }
}

private fun BedrockParticleFile.Curve.eval(context: BedrockContext): Float {
    val range = range.eval(context)
    val input = input.eval(context) / range

    return when (type) {
        BedrockParticleFile.Curve.Type.Linear -> {
            val position = input * nodes.lastIndex
            val index = position.toInt()
            when {
                index < 0 -> nodes.first().eval(context)
                index >= nodes.lastIndex -> nodes.last().eval(context)
                else -> {
                    val alpha = position - index
                    nodes[index].eval(context).lerp(nodes[index + 1].eval(context), alpha)
                }
            }
        }

        BedrockParticleFile.Curve.Type.Bezier -> {
            bezier(
                input,
                nodes[0].eval(context),
                nodes[1].eval(context),
                nodes[2].eval(context),
                nodes[3].eval(context),
            )
        }

        BedrockParticleFile.Curve.Type.CatmullRom -> {
            val position = 1 + input * (nodes.lastIndex - 2)
            val index = position.toInt()
            when {
                index < 1 -> nodes[1].eval(context)
                index >= nodes.lastIndex - 1 -> nodes[nodes.lastIndex - 1].eval(context)
                else -> {
                    val alpha = position - index
                    catmullRom(
                        alpha,
                        nodes[index - 1].eval(context),
                        nodes[index].eval(context),
                        nodes[index + 1].eval(context),
                        nodes[index + 2].eval(context),
                    )
                }
            }
        }

        BedrockParticleFile.Curve.Type.BezierChain -> {
            val segmentCount = (nodes.size - 1) / 3
            val position = input * segmentCount
            val segmentIndex = position.toInt()
            val alpha = position - segmentIndex

            if (segmentIndex < 0) return nodes.first().eval(context)
            if (segmentIndex >= segmentCount) return nodes.last().eval(context)

            val i = segmentIndex * 3
            return bezier(
                alpha,
                nodes[i].eval(context),
                nodes[i + 1].eval(context),
                nodes[i + 2].eval(context),
                nodes[i + 3].eval(context)
            )
        }
    }
}