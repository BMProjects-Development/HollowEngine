package ru.hollowhorizon.hollowengine.common.ide.session.definition

import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.resolveToCallCandidates
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.asJava.toLightElements
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
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
    symbol.psi?.let(::definitionForPsi)

internal fun ScriptingAnalyzerImpl.definitionForPsi(element: PsiElement): DefinitionLocation? {
    val file = element.containingFile ?: return null
    val virtualFile = file.virtualFile
    if (virtualFile?.extension != "class" && !file.name.endsWith(".class")) {
        val offset = (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.startOffset
            ?: (element as? KtNamedDeclaration)?.nameIdentifier?.textRange?.startOffset
            ?: element.textRange.startOffset
        if (virtualFile == null || "!/" !in virtualFile.path) {
            if (virtualFile?.fileSystem?.protocol == "file") return DefinitionLocation(virtualFile.path, offset, file.text, true)
            return DefinitionLocation(file.name, offset)
        }
        return DefinitionLocation("library-sources/${virtualFile.path}", offset, file.text, true)
    }
    val target = SourceDeclarationIdentity.from(element) ?: return null
    val packageName = when (file) {
        is KtFile -> file.packageFqName.asString()
        is PsiClassOwner -> file.packageName
        else -> ""
    }
    librarySources.find(target, virtualFile, packageName)?.let {
        projectStructureProvider.registerLibrarySource(it.path, element)
        return it
    }
    val jvmDeclarations = if (element is KtElement) element.toLightElements() else listOf(element)
    for (declaration in jvmDeclarations) {
        val jvmTarget = SourceDeclarationIdentity.from(declaration) ?: continue
        val binary = declaration.containingFile?.virtualFile ?: virtualFile ?: continue
        definitionDecompiler.decompile(binary, jvmTarget, sourceDeclarationMatcher)?.let { return it }
    }
    if (element is KtElement && virtualFile != null) {
        kotlinJvmFunction(virtualFile, target)?.let { (binary, jvmTarget) ->
            definitionDecompiler.decompile(binary, jvmTarget, sourceDeclarationMatcher)?.let { return it }
        }
    }
    return null
}
