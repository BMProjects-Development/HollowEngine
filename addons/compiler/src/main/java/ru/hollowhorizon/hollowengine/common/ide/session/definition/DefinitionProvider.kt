package ru.hollowhorizon.hollowengine.common.ide.session.definition

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.resolveToCallCandidates
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import ru.hollowhorizon.hollowengine.common.ide.session.ScriptingAnalyzerImpl
import ru.hollowhorizon.hollowengine.common.ide.session.inlays.resourceLocationDefinition
import ru.hollowhorizon.hollowengine.common.scripting.ide.DefinitionLocation

fun ScriptingAnalyzerImpl.findDefinition(file: KtFile, offset: Int): DefinitionLocation? {
    resourceLocationDefinition(file, offset)?.let { return it }
    val expression = file.referenceExpressionAt(offset) ?: return null
    return analyze(file) {
        val call = expression.parent as? KtCallExpression
        val resolvedCall = call?.takeIf { it.calleeExpression == expression }?.resolveToCallCandidates()
            ?.filter { it.isInBestCandidates }?.mapNotNull { (it.candidate as? KaFunctionCall<*>)
                ?.symbol }?.distinct()?.singleOrNull()
        val symbol = resolvedCall ?: expression.mainReference.resolveToSymbols()
            .filterIsInstance<KaDeclarationSymbol>().singleOrNull() ?: return@analyze null
        definitionForSymbol(symbol)
    }
}

private fun KtFile.referenceExpressionAt(offset: Int): KtSimpleNameExpression? {
    if (textLength == 0) return null
    val safeOffset = offset.coerceIn(0, textLength)
    return sequenceOf(safeOffset, safeOffset - 1).filter { it in 0 until textLength }
        .mapNotNull(::findElementAt).flatMap { it.parentsWithSelf }
        .filterIsInstance<KtSimpleNameExpression>().firstOrNull()
}

internal fun ScriptingAnalyzerImpl.definitionForSymbol(symbol: KaDeclarationSymbol): DefinitionLocation? =
    symbol.psi?.let(navigation::definitionFor)
