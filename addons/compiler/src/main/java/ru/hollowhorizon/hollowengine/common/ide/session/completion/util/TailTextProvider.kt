package ru.hollowhorizon.hollowengine.common.ide.session.completion.util

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.renderer.types.KaExpandedTypeRenderingMode
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.receiverType
import org.jetbrains.kotlin.analysis.api.symbols.typeParameters
import org.jetbrains.kotlin.analysis.api.types.KaFunctionType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.renderer.render
import org.jetbrains.kotlin.types.Variance

@KaExperimentalApi
private val completionTypeRenderer = KaTypeRendererForSource.WITH_SHORT_NAMES_WITHOUT_PARAMETER_NAMES.with {
    expandedTypeRenderingMode = KaExpandedTypeRenderingMode.RENDER_ABBREVIATED_TYPE_WITH_EXPANDED_TYPE_COMMENT
}

object TailTextProvider {
    context(session: KaSession)
    fun getTailText(
        symbol: KaCallableSymbol,
        useFqName: Boolean = false,
    ): String = with(session) {
        buildString {
            symbol.getContainerOrAliasPresentation(useFqName = useFqName)?.let {
                append(it)
                return@buildString
            }

            symbol.receiverType?.let { renderReceiverType(it) }
        }
    }

    context(_: KaSession)
    @OptIn(KaExperimentalApi::class)
    fun getTailText(
        symbol: KaClassLikeSymbol,
        usePackageFqName: Boolean = false,
        addTypeParameters: Boolean = true,
        useFqnAsTailText: Boolean = false,
    ): String = buildString {
        symbol.classId?.let { classId ->
            if (addTypeParameters && symbol.typeParameters.isNotEmpty()) {
                // We want to render type parameter names without modifiers and bounds, so no renderer is required.
                append(symbol.typeParameters.joinToString(", ", "<", ">") { it.name.render() })
            }

            val fqName = if (useFqnAsTailText) {
                classId.asSingleFqName()
            } else if (usePackageFqName) {
                classId.packageFqName
            } else {
                classId.asSingleFqName().parent()
            }

            append(" (")
            append(fqName.asStringForTailText())
            append(")")
        }
    }

    context(s: KaSession)
    private fun StringBuilder.renderReceiverType(receiverType: KaType) {
        val renderedType = receiverType.renderVerbose()
        append(renderedType)
    }

    context(_: KaSession)
    private fun KaCallableSymbol.getContainerOrAliasPresentation(
        isFunctionalVariableCall: Boolean = false,
        useFqName: Boolean = false,
    ): String? {
        return if (useFqName) {
            val callableId = callableId ?: return null
            val renderedAliasName = callableId.asSingleFqName().asStringForTailText()
            " ($renderedAliasName)"
        } else {
            getContainerPresentation(isFunctionalVariableCall = isFunctionalVariableCall)
        }
    }

    context(session: KaSession)
    private fun KaCallableSymbol.getContainerPresentation(isFunctionalVariableCall: Boolean): String? = with(session) {
        val callableId = callableId ?: return null
        val className = callableId.className

        val isExtensionCall = isExtensionCall(isFunctionalVariableCall)
        val packagePresentation = callableId.packageName.asStringForTailText()
        return when {
            !isExtensionCall && className != null -> null
            !isExtensionCall -> " ($packagePresentation)"

            else -> {
                className?.asString() ?: packagePresentation
            }
        }
    }

    private fun FqName.asStringForTailText(): String =
        if (isRoot) "<root>" else render()
}

context(session: KaSession)
internal fun KaType.renderVerbose(): String = with(session) {
    render(
        renderer = completionTypeRenderer,
        position = Variance.INVARIANT,
    )
}

context(_: KaSession)
internal fun KaCallableSymbol.isExtensionCall(isFunctionalVariableCall: Boolean): Boolean =
    isExtension || isFunctionalVariableCall && (returnType as? KaFunctionType)?.hasReceiver == true
