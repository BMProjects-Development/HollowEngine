package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KVariance
import kotlin.reflect.full.isSubclassOf
import ru.hollowhorizon.hollowengine.common.scripting.ScriptingEnvironment
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings

/** Kotlin types as nodes keep them, written out. */
object StoryKotlinTypes {
    private const val STAR = "*"

    /** How Kotlin writes [type]; null when no text can name it, as with a type parameter or a function. */
    fun name(type: KType): String? {
        val kind = type.classifier as? KClass<*> ?: return null
        if (kind.isSubclassOf(Function::class)) return null
        val base = className(kind) ?: return null
        val arguments = type.arguments.map { argument ->
            val nested = argument.type ?: return@map STAR
            val variance = when (argument.variance) {
                KVariance.IN -> "in "
                KVariance.OUT -> "out "
                else -> ""
            }
            variance + (name(nested) ?: return null)
        }
        val listed = if (arguments.isEmpty()) "" else arguments.joinToString(prefix = "<", postfix = ">")
        return base + listed + if (type.isMarkedNullable) "?" else ""
    }

    private class Names(val mappings: Mappings, val classes: Map<String, String>)

    private val namesLock = Any()
    private var names = Names(Mappings.EMPTY, emptyMap())

    internal var mappings: () -> Mappings = { ScriptingEnvironment.currentOrNull()?.mappings ?: Mappings.EMPTY }

    /** Names a known runtime class on the compiler's named classpath, including nested classes. */
    fun className(kind: KClass<*>): String? = className(kind, mappings())

    /** The name of [kind] without its package, `Outer.Inner` for a nested class, as scripts write it. */
    fun shortName(kind: KClass<*>): String? = className(kind)?.let(StoryPinType::shortName)

    internal fun className(kind: KClass<*>, mappings: Mappings): String? {
        val qualified = kind.qualifiedName ?: return null
        val cached = synchronized(namesLock) {
            names.takeIf { it.mappings === mappings } ?: run {
                val runtime = mappings.namespaces.indexOf(RUNTIME)
                val target = mappings.namespaces.indexOf(NAMED)
                val classes = if (runtime < 0 || target < 0) emptyMap() else mappings.classes.associate { type ->
                    dotted(type.names[runtime]) to dotted(type.names[target])
                }
                Names(mappings, classes).also { names = it }
            }
        }
        return cached.classes[qualified] ?: qualified
    }

    private fun dotted(name: String) = name.replace('/', '.').replace('$', '.')

    private const val RUNTIME = "intermediary"
    private const val NAMED = "named"
}
