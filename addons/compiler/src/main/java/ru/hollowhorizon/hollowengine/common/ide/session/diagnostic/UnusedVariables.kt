package ru.hollowhorizon.hollowengine.common.ide.session.diagnostic

import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.base.KaConstantValue
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaJavaFieldSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPackageSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import ru.hollowhorizon.hollowengine.common.scripting.ide.DiagnosticFix
import ru.hollowhorizon.hollowengine.common.scripting.ide.TextEdit

context(session: KaSession)
internal fun unusedVariableFix(property: KtProperty): DiagnosticFix? {
    if (!property.isLocal || property.hasDelegate() || property.accessors.isNotEmpty()) return null
    val initializer = property.initializer
    val replacement = if (initializer == null || initializer.canDiscardInitializer()) "" else {
        property.typeReference?.text?.let { "run<$it> { ${initializer.text} }" } ?: initializer.text
    }
    val range = property.textRange
    var start = range.startOffset
    var end = range.endOffset
    if (replacement.isEmpty()) {
        val text = property.containingFile.text
        val lineStart = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        if (text.substring(lineStart, start).isBlank() && text.substring(end, lineEnd).isBlank()) {
            start = lineStart
            end = (lineEnd + 1).coerceAtMost(text.length)
        }
    }
    return DiagnosticFix("hollowengine.gui.ide.editor.fix.remove_unused_variable", listOf(TextEdit(start, end, replacement)))
}

context(session: KaSession)
private fun KtExpression.canDiscardInitializer(): Boolean = with(session) {
    val constant = this@canDiscardInitializer.evaluate()
    if (constant != null && constant !is KaConstantValue.ErrorValue) return true
    if (this@canDiscardInitializer is KtParenthesizedExpression) return expression?.canDiscardInitializer() == true
    val reference = when (val expression = this@canDiscardInitializer) {
        is KtSimpleNameExpression -> expression
        is KtDotQualifiedExpression -> {
            if (!expression.receiverExpression.isStaticQualifier()) return false
            expression.selectorExpression as? KtSimpleNameExpression
        }
        else -> null
    } ?: return false
    val symbol = reference.mainReference.resolveToSymbols().singleOrNull() as? KaJavaFieldSymbol ?: return false
    val field = symbol.psi as? PsiField ?: return false
    return listOf(PsiModifier.PUBLIC, PsiModifier.STATIC, PsiModifier.FINAL).all(field::hasModifierProperty)
}

context(session: KaSession)
private fun KtExpression.isStaticQualifier(): Boolean = with(session) {
    val reference = when (val expression = this@isStaticQualifier) {
        is KtSimpleNameExpression -> expression
        is KtDotQualifiedExpression -> {
            if (!expression.receiverExpression.isStaticQualifier()) return false
            expression.selectorExpression as? KtSimpleNameExpression
        }
        else -> null
    } ?: return false
    return when (reference.mainReference.resolveToSymbols().singleOrNull()) {
        is KaClassLikeSymbol, is KaPackageSymbol -> true
        else -> false
    }
}
