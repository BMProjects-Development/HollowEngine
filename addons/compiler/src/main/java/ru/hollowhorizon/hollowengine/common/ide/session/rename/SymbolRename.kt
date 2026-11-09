package ru.hollowhorizon.hollowengine.common.ide.session.rename

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import ru.hollowhorizon.hollowengine.common.scripting.ide.RenameTarget
import ru.hollowhorizon.hollowengine.common.scripting.ide.TextEdit

internal data class DeclarationKey(val path: String, val chain: String, val project: Boolean)

internal data class RenamedDeclaration(val key: DeclarationKey, val name: String, val target: RenameTarget)

internal object SymbolRename {
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun target(file: KtFile, offset: Int, editable: (String) -> Boolean): RenamedDeclaration? {
        val leaf = identifierAt(file, offset) ?: return null
        val key = analyze(file) { keysOf(leaf).firstOrNull() } ?: return null
        if (!editable(key.path)) return null
        val name = leaf.text.removeSurrounding("`")
        return RenamedDeclaration(key, name, RenameTarget(name, leaf.textRange.startOffset, leaf.textRange.endOffset))
    }

    fun accepts(name: String): Boolean = identifier.matches(name)

    fun edits(file: KtFile, key: DeclarationKey, oldName: String, newName: String): List<TextEdit> {
        val written = if (newName in hardKeywords) "`$newName`" else newName
        val edits = ArrayList<TextEdit>()
        analyze(file) {
            var leaf: PsiElement? = PsiTreeUtil.firstChild(file)
            while (leaf != null) {
                if (leaf.node?.elementType == KtTokens.IDENTIFIER &&
                    leaf.text.removeSurrounding("`") == oldName &&
                    key in keysOf(leaf)
                ) {
                    edits += TextEdit(leaf.textRange.startOffset, leaf.textRange.endOffset, written)
                }
                leaf = PsiTreeUtil.nextLeaf(leaf)
            }
        }
        return edits
    }

    private fun identifierAt(file: KtFile, offset: Int): PsiElement? {
        val safe = offset.coerceIn(0, file.textLength)
        return listOfNotNull(
            file.findElementAt(safe), if (safe > 0) file.findElementAt(safe - 1) else null
        ).firstOrNull { it.node?.elementType == KtTokens.IDENTIFIER }
    }

    context(session: KaSession)
    private fun keysOf(leaf: PsiElement): List<DeclarationKey> = with(session) {
        val parent = leaf.parent
        val symbols: List<KaSymbol> = runCatching {
            when (parent) {
                is KtSimpleNameExpression -> parent.mainReference.resolveToSymbols().toList()
                is KtNamedDeclaration if parent.nameIdentifier == leaf -> listOf(parent.symbol)
                else -> emptyList()
            }
        }.getOrElse { emptyList() }

        symbols.mapNotNull { symbol ->
            val declared = if (symbol is KaConstructorSymbol) symbol.containingSymbol ?: symbol else symbol
            (declared.psi as? KtNamedDeclaration)?.let(::keyOf)
        }
    }

    private fun keyOf(declaration: KtNamedDeclaration): DeclarationKey? {
        val file = declaration.containingFile as? KtFile ?: return null
        val local = isLocal(declaration)
        val chain = buildString {
            declaration.parentsWithSelf.filterIsInstance<KtNamedDeclaration>().toList().asReversed().forEach {
                append('/').append(it.name)
                if (it is KtFunction) append('(').append(it.valueParameters.size).append(')')
            }
            if (local) append('@').append(declaration.textOffset)
        }
        val private = declaration.parentsWithSelf.filterIsInstance<KtModifierListOwner>()
            .any { it.hasModifier(KtTokens.PRIVATE_KEYWORD) }
        return DeclarationKey(file.name, chain, project = !local && !private)
    }

    private fun isLocal(declaration: KtNamedDeclaration): Boolean = declaration.parentsWithSelf.drop(1).any { parent ->
        parent is KtFunctionLiteral || parent is KtBlockExpression && parent.parent !is KtScript
    }

    private val hardKeywords = KtTokens.KEYWORDS.types.map { it.toString() }.toSet()
}
