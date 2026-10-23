package ru.hollowhorizon.hollowengine.client.models.internal.animator

import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockContext
import ru.hollowhorizon.hollowengine.client.models.bedrock.Variables

/** Bridges imported Molang to the same entity, variables and scratch space as the animator. */
internal fun animationMolangContext(context: AnimatorEvaluationContext): BedrockContext = BedrockContext(
    context,
    AnimationMolangVariables(context),
    context.temporaries,
)

private class AnimationMolangVariables(private val context: AnimatorEvaluationContext) : Variables {
    private val references = HashMap<String, Variables.Variable>()

    override fun getOrNull(name: String): Variables.Variable? = if (context.hasStoredVariable(name)) reference(name) else null

    override fun getOrPut(name: String, initialValue: Float): Variables.Variable {
        if (!context.hasStoredVariable(name)) context.setVariable(name, initialValue)
        return reference(name)
    }

    private fun reference(name: String): Variables.Variable = references.getOrPut(name) {
        object : Variables.Variable {
            override fun get(): Float = context.storedVariable(name)
            override fun set(value: Float) = context.setVariable(name, value)
        }
    }
}
