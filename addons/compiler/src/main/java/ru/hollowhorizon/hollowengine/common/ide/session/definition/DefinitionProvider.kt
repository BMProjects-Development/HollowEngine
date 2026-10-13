package ru.hollowhorizon.hollowengine.common.ide.session.definition

import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import org.objectweb.asm.ClassReader
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.components.resolveToCallCandidates
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.lexer.KtTokens
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

internal fun ScriptingAnalyzerImpl.definitionForSymbol(symbol: KaDeclarationSymbol): DefinitionLocation? {
    val annotation = symbol.annotations.firstOrNull { it.classId?.asFqNameString() == "kotlin.jvm.JvmName" }
    val jvmName = (annotation?.arguments?.firstOrNull()?.expression as? KaAnnotationValue.ConstantValue)?.value?.value as? String
    return symbol.psi?.let { definitionForPsi(it, jvmName) }
}

internal fun ScriptingAnalyzerImpl.definitionForPsi(element: PsiElement, jvmName: String? = null): DefinitionLocation? {
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
    librarySources.find(target, virtualFile, packageName)?.let { return it }
    return virtualFile?.let { binary ->
        val binaryOwners = ClassReader(binary.contentsToByteArray()).className.substringAfterLast('/').split('$')
        val jvmTarget = if (element is KtNamedFunction || element is KtProperty) {
            target.copy(owners = binaryOwners)
        } else target
        val functionTarget = if (element is KtNamedFunction) {
            val psiJvmName = element.annotationEntries.firstOrNull { it.shortName?.asString() == "JvmName" }
                ?.valueArguments?.firstOrNull()?.getArgumentExpression() as? KtStringTemplateExpression
            jvmTarget.copy(
                name = jvmName ?: psiJvmName?.entries?.singleOrNull()?.text ?: jvmTarget.name,
                parameters = jvmTarget.parameters + if (element.hasModifier(KtTokens.SUSPEND_KEYWORD)) listOf("kotlin.coroutines.Continuation") else emptyList(),
            )
        } else jvmTarget
        definitionDecompiler.decompile(binary, functionTarget, sourceDeclarationMatcher)?.let { return it }
        if (element is KtProperty) {
            val getter = if (target.name.startsWith("is") && target.name.getOrNull(2)?.isUpperCase() == true) target.name
                else "get${target.name.replaceFirstChar(Char::uppercaseChar)}"
            definitionDecompiler.decompile(binary, jvmTarget.copy(name = getter, kind = SourceDeclarationIdentity.Kind.FUNCTION), sourceDeclarationMatcher)
        } else null
    }
}
