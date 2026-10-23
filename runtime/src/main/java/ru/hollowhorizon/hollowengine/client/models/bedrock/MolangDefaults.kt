package ru.hollowhorizon.hollowengine.client.models.bedrock

import ru.hollowhorizon.hollowengine.HollowEngine
import java.util.concurrent.ConcurrentHashMap

/** Authored fallback expressions; evaluated in the caller's scope without becoming stored variables. */
class MolangDefaults(expressions: Map<String, FloatExpr>) {
    private val expressions = expressions.toMap()
    private val warnedCycles = ConcurrentHashMap.newKeySet<String>()

    fun contains(name: String): Boolean = "variable.$name" in expressions

    internal fun value(name: String, context: BedrockContext): Float? {
        val expression = expressions[name] ?: return null
        if (!context.resolvingDefaults.add(name)) {
            if (warnedCycles.add(name)) HollowEngine.LOGGER.warn("Cyclic Molang placeholder '{}'", name)
            return 0f
        }
        return try {
            expression.eval(context)
        } finally {
            context.resolvingDefaults.remove(name)
        }
    }

    internal fun bind(primary: Variables, context: () -> BedrockContext): Variables {
        if (expressions.isEmpty()) return primary
        return object : Variables {
            override fun getOrNull(name: String): Variables.Variable? = primary.getOrNull(name) ?: if (contains(name)) {
                object : Variables.Variable {
                    override fun get(): Float = primary.getOrNull(name)?.get() ?: value("variable.$name", context()) ?: 0f
                    override fun set(value: Float) { primary[name] = value }
                }
            } else null

            override fun getOrPut(name: String, initialValue: Float): Variables.Variable =
                primary.getOrPut(name, initialValue)
        }
    }

    companion object {
        val EMPTY = MolangDefaults(emptyMap())
    }
}
