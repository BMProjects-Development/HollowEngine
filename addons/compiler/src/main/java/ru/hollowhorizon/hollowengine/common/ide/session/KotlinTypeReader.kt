package ru.hollowhorizon.hollowengine.common.ide.session

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.typeParameters
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.types.Variance
import ru.hollowhorizon.hollowengine.common.scripting.ide.*


internal class KotlinTypeReader(private val session: KaSession) {
    private val seen = HashSet<String>()
    private val pending = ArrayDeque<Pair<String, ClassId>>()

    @OptIn(KaExperimentalApi::class)
    fun declared(type: KaType): KotlinType? = with(session) {
        read(type.approximateToDenotableSupertypeOrSelf(allowLocalDenotableTypes = false))
    }

    fun classes(): Map<String, KotlinClassInfo> = with(session) {
        val described = LinkedHashMap<String, KotlinClassInfo>()
        while (pending.isNotEmpty()) {
            val (name, id) = pending.removeFirst()
            val symbol = findClass(id)
            described[name] = KotlinClassInfo(
                name,
                symbol?.typeParameters.orEmpty().map { KotlinTypeParameter(it.name.asString(), variance(it.variance)) },
                symbol?.superTypes.orEmpty().mapNotNull { read(it) as? KotlinType.Class },
                symbol?.classKind == KaClassKind.INTERFACE,
            )
        }
        described
    }

    private fun read(type: KaType): KotlinType? = with(session) {
        when (val expanded = type.fullyExpandedType) {
            is KaFlexibleType -> read(expanded.upperBound)?.nullable(expanded.lowerBound.isMarkedNullable)
            is KaDefinitelyNotNullType -> read(expanded.original)?.nullable(false)
            is KaTypeParameterType -> KotlinType.Parameter(expanded.name.asString(), expanded.isMarkedNullable)
            is KaClassType -> {
                val id = expanded.classId.takeUnless { it.isLocal } ?: return null
                val arguments = expanded.typeArguments.map { argument ->
                    when (argument) {
                        is KaStarTypeProjection -> KotlinTypeArgument.STAR
                        is KaTypeArgumentWithVariance -> KotlinTypeArgument(
                            read(argument.type) ?: return null,
                            variance(argument.variance)
                        )
                    }
                }
                val name = id.asFqNameString()
                if (seen.add(name)) pending += name to id
                KotlinType.Class(name, arguments, expanded.isMarkedNullable)
            }

            else -> null
        }
    }

    private fun variance(variance: Variance) = when (variance) {
        Variance.INVARIANT -> KotlinVariance.INVARIANT
        Variance.IN_VARIANCE -> KotlinVariance.IN
        Variance.OUT_VARIANCE -> KotlinVariance.OUT
    }
}
