package ru.hollowhorizon.hollowengine.common.ide.session

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.renderer.types.impl.KaTypeRendererForSource
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getQualifiedExpressionForSelector
import org.jetbrains.kotlin.types.Variance
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinName
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinSyntax
import ru.hollowhorizon.hollowengine.common.scripting.ide.KotlinToken
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType

private const val PREFIX = "fun snippet() {\n"
private const val SUFFIX = "\n}"

/** Parses [text] as the body of a function, which takes an expression and statements alike. */
internal fun kotlinSyntax(factory: KtPsiFactory, text: String): KotlinSyntax {
    val file = factory.createFile("snippet.kt", PREFIX + text + SUFFIX)
    val body = (file.declarations.singleOrNull() as? KtNamedFunction)?.bodyBlockExpression
        ?: return KotlinSyntax(valid = false)
    val valid = PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) == null
    return KotlinSyntax(valid, arguments(body, text), names(body), tokens(body))
}

/** The types of the properties of [file] whose names start with [prefix], as Kotlin writes them with packages. */
@OptIn(KaExperimentalApi::class)
internal fun propertyTypes(file: KtFile, prefix: String): Map<String, String> = analyze(file) {
    file.collectDescendantsOfType<KtProperty> { it.name?.startsWith(prefix) == true }.mapNotNull { property ->
        val type = property.symbol.returnType.takeUnless { it is KaErrorType } ?: return@mapNotNull null
        property.name!! to type.render(KaTypeRendererForSource.WITH_QUALIFIED_NAMES, Variance.INVARIANT)
    }.toMap()
}

/** The leaves of [body] that have a color of their own. */
private fun tokens(body: KtBlockExpression): List<KotlinToken> = body.collectDescendantsOfType<LeafPsiElement>().mapNotNull { leaf ->
    val type = when (leaf.elementType) {
        in KtTokens.KEYWORDS -> TokenType.KEYWORD
        in KtTokens.SOFT_KEYWORDS -> if (leaf.parent is KtModifierList) TokenType.KEYWORD else return@mapNotNull null
        in KtTokens.STRINGS, KtTokens.ESCAPE_SEQUENCE -> TokenType.STRING
        KtTokens.INTEGER_LITERAL, KtTokens.FLOAT_LITERAL -> TokenType.NUMERIC_LITERAL
        in KtTokens.COMMENTS -> TokenType.COMMENT
        else -> return@mapNotNull null
    }
    val start = leaf.textRange.startOffset - PREFIX.length
    KotlinToken(start, start + leaf.textLength, type)
}

/** The text between the commas of the call [body] consists of, commas of what it holds aside. */
private fun arguments(body: KtBlockExpression, text: String): List<String>? {
    val list = (body.statements.singleOrNull() as? KtCallExpression)?.valueArgumentList ?: return null
    if (list.rightParenthesis?.textRange?.endOffset != PREFIX.length + text.length) return null
    return split(list, text)
}

private fun split(list: KtValueArgumentList, text: String): List<String> {
    val commas =
        list.node.getChildren(null).filter { it.elementType == KtTokens.COMMA }.map { it.startOffset - PREFIX.length }
    val open = list.leftParenthesis!!.textRange.endOffset - PREFIX.length
    val close = list.rightParenthesis!!.textRange.startOffset - PREFIX.length
    val starts = listOf(open) + commas.map { it + 1 }
    return starts.zip(commas + close) { start, end -> text.substring(start, end).trim() }
}

/** The names that mean something declared outside of [body]: its own locals and members of other things do not. */
private fun names(body: KtBlockExpression): List<KotlinName> {
    val local = PsiTreeUtil.collectElementsOfType(body, KtDeclaration::class.java)
        .filter { it is KtProperty || it is KtParameter }.mapNotNullTo(HashSet()) { it.name }
    return PsiTreeUtil.collectElementsOfType(body, KtNameReferenceExpression::class.java).filter { name ->
        val parent = name.parent
        name.getReferencedName() !in local && name.getQualifiedExpressionForSelector() == null && parent !is KtValueArgumentName && parent !is KtUserType && (parent as? KtCallExpression)?.calleeExpression != name
    }.map { name ->
        val start = name.textRange.startOffset - PREFIX.length
        KotlinName(name.getReferencedName(), start, start + name.textLength)
    }.sortedBy { it.start }
}
