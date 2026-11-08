package ru.hollowhorizon.hollowengine.common.ide.session

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.name.ClassIdBasedLocality
import org.jetbrains.kotlin.types.Variance

/** Types analysis resolved, written the way Kotlin writes them with every class named in full, and which go into which. */
internal class KotlinTypeTexts(private val session: KaSession) {
    private val found = LinkedHashMap<String, KaType>()

    @OptIn(KaExperimentalApi::class)
    fun declared(type: KaType): String? = with(session) {
        val platform = (type.fullyExpandedType as? KaFlexibleType)?.lowerBound?.isMarkedNullable == false
        val approximated = type.approximateToDenotableSupertypeOrSelf(allowLocalDenotableTypes = false)
        val denotable = if (platform) approximated.withNullability(false) else approximated
        text(denotable)?.also { found.putIfAbsent(it, denotable) }
    }

    fun fits(): Map<String, Set<String>> = with(session) {
        found.mapValues { (_, type) ->
            val value = type.withNullability(false)
            found.filterValues { value.isSubtypeOf(it) }.keys
        }
    }

    @OptIn(ClassIdBasedLocality::class)
    private fun text(type: KaType): String? = with(session) {
        when (val expanded = type.fullyExpandedType) {
            is KaFlexibleType -> text(expanded.upperBound)?.removeSuffix("?")
                ?.plus(if (expanded.lowerBound.isMarkedNullable) "?" else "")

            is KaDefinitelyNotNullType -> text(expanded.original)?.removeSuffix("?")
            is KaTypeParameterType -> expanded.name.asString() + if (expanded.isMarkedNullable) "?" else ""
            is KaClassType -> {
                val id = expanded.classId.takeUnless { it.isLocal } ?: return null
                val arguments = expanded.typeArguments.map { argument ->
                    when (argument) {
                        is KaStarTypeProjection -> "*"
                        is KaTypeArgumentWithVariance -> keyword(argument.variance) + (text(argument.type)
                            ?: return null)
                    }
                }
                val listed = if (arguments.isEmpty()) "" else arguments.joinToString(prefix = "<", postfix = ">")
                id.asFqNameString() + listed + if (expanded.isMarkedNullable) "?" else ""
            }

            else -> null
        }
    }

    private fun keyword(variance: Variance) = when (variance) {
        Variance.INVARIANT -> ""
        Variance.IN_VARIANCE -> "in "
        Variance.OUT_VARIANCE -> "out "
    }
}
