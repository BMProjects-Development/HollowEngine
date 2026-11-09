package ru.hollowhorizon.hollowengine.common.ide.session

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.declarations.KaRendererTypeApproximator
import org.jetbrains.kotlin.analysis.api.renderer.types.KaExpandedTypeRenderingMode
import org.jetbrains.kotlin.analysis.api.renderer.types.KaTypeRenderer
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.renderer.types.renderers.KaFlexibleTypeRenderer
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaEnumEntrySymbol
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.analysis.utils.printer.PrettyPrinter
import org.jetbrains.kotlin.name.ClassIdBasedLocality
import org.jetbrains.kotlin.types.Variance
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinTypeInfo

/** Types analysis resolved, written the way Kotlin writes them with every class named in full, and which go into which. */
internal class KotlinTypeTexts(private val session: KaSession) {
    private val found = LinkedHashMap<String, KaType>()

    @OptIn(KaExperimentalApi::class)
    fun declared(type: KaType): String? = with(session) {
        val platform = (type.fullyExpandedType as? KaFlexibleType)?.lowerBound?.isMarkedNullable == false
        val resolved = if (platform) type.withNullability(false) else type
        val presented = resolved.takeIf(::usable)
            ?: resolved.approximateToDenotableSupertypeOrSelf(allowLocalDenotableTypes = false).takeIf(::usable)
            ?: return null
        presented.render(RENDERER, Variance.INVARIANT).also { found.putIfAbsent(it, presented) }
    }

    fun fits(): Map<String, Set<String>> = with(session) {
        found.mapValues { (_, type) ->
            val value = type.withNullability(false)
            found.filterValues { value.isSubtypeOf(it) }.keys
        }
    }

    fun details(): Map<String, KotlinTypeInfo> = found.mapValues { (_, type) -> info(type) }

    private fun info(type: KaType): KotlinTypeInfo = with(session) {
        val simple = when (val expanded = type.fullyExpandedType) {
            is KaFlexibleType -> expanded.lowerBound
            is KaDefinitelyNotNullType -> expanded.original
            else -> expanded
        }
        val classifier = (simple as? KaClassType)?.classId?.asFqNameString()
        val parents = LinkedHashSet<String>()
        fun visit(current: KaType) {
            if (current is KaIntersectionType) {
                current.conjuncts.forEach(::visit)
                return
            }
            val symbol = current.expandedSymbol ?: return
            val name = symbol.classId?.asFqNameString() ?: return
            if (parents.add(name)) symbol.superTypes.forEach(::visit)
        }
        visit(simple)
        fun containsNothing(current: KaType): Boolean = when (val value = current.fullyExpandedType) {
            is KaFlexibleType -> containsNothing(value.lowerBound)
            is KaDefinitelyNotNullType -> containsNothing(value.original)
            is KaIntersectionType -> value.conjuncts.any(::containsNothing)
            is KaClassType -> value.classId.asFqNameString() == "kotlin.Nothing" || value.typeArguments.any {
                (it as? KaTypeArgumentWithVariance)?.type?.let(::containsNothing) == true
            }

            else -> false
        }

        val symbol = simple.expandedSymbol
        val entries =
            symbol?.takeIf { it.classKind == KaClassKind.ENUM_CLASS }?.staticDeclaredMemberScope?.callables?.filterIsInstance<KaEnumEntrySymbol>()
                ?.map { it.name.asString() }?.toList()
        KotlinTypeInfo(classifier, parents, type.isMarkedNullable, containsNothing(type), entries)
    }

    @OptIn(ClassIdBasedLocality::class)
    private fun usable(type: KaType): Boolean = with(session) {
        when (val expanded = type.fullyExpandedType) {
            is KaFlexibleType -> usable(expanded.lowerBound) && usable(expanded.upperBound)
            is KaDefinitelyNotNullType -> usable(expanded.original)
            is KaIntersectionType -> expanded.conjuncts.all(::usable)
            is KaTypeParameterType -> true
            is KaClassType -> !expanded.classId.isLocal && expanded.typeArguments.all {
                (it as? KaTypeArgumentWithVariance)?.type?.let(::usable) != false
            }

            else -> false
        }
    }

    private companion object {
        val RENDERER = KaTypeRendererForSource.WITH_QUALIFIED_NAMES_WITHOUT_PARAMETER_NAMES.with {
            expandedTypeRenderingMode = KaExpandedTypeRenderingMode.RENDER_EXPANDED_TYPE
            typeApproximator = KaRendererTypeApproximator.NO_APPROXIMATION
            flexibleTypeRenderer = object : KaFlexibleTypeRenderer {
                override fun renderType(
                    analysisSession: KaSession,
                    type: KaFlexibleType,
                    typeRenderer: KaTypeRenderer,
                    printer: PrettyPrinter,
                ) = with(analysisSession) {
                    typeRenderer.renderType(
                        this,
                        type.upperBound.withNullability(type.lowerBound.isMarkedNullable),
                        printer
                    )
                }
            }
        }
    }
}
