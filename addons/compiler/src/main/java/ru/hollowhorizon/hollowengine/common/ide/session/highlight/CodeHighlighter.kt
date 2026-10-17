package ru.hollowhorizon.hollowengine.common.ide.session.highlight

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotation
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstanceOrNull
import ru.hollowhorizon.hollowengine.client.HighlightTheme
import ru.hollowhorizon.hollowengine.common.ide.session.inlays.provideHints
import ru.hollowhorizon.hollowengine.common.scripting.ide.InlayHint
import ru.hollowhorizon.hollowengine.common.scripting.ide.OccurrenceRange
import ru.hollowhorizon.hollowengine.common.scripting.ide.SpanStyle
import ru.hollowhorizon.hollowengine.common.scripting.ide.TextLine
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType

class LineBuilder(val lines: MutableList<TextLine>) {

    fun append(builder: SpanBuilder.() -> Unit) {
        lines.add(SpanBuilder(arrayListOf()).apply(builder).build())
    }

    class SpanBuilder(private val spans: MutableList<Pair<String, SpanStyle>>) {
        var tokenType = TokenType.DEFAULT
        var italic = false
        var bold = false
        var highlight = false

        var colorOverride: Int? = null
        private val hints = ArrayList<InlayHint>()


        fun append(text: String) {
            spans.add(text to SpanStyle(tokenType, italic, bold, highlight, colorOverride))
            italic = false
            bold = false
            highlight = false
            colorOverride = null
        }

        fun appendHint(hint: InlayHint) {
            hints.add(hint)
        }

        fun build() = TextLine(spans, hints)
    }
}

fun highlightCode(file: KtFile, offset: Int): List<TextLine> {
    val code = file.text
    val lineCount = code.lines().size
    val builder = LineBuilder(arrayListOf())

    val elementAtCaret = file.findElementAtCaret(offset)

    analyze(file) {
        val hints = runCatching { provideHints(file) }.getOrDefault(emptyList())

        // Предварительно вычисляем ключи символов под курсором
        val caretKeys = elementAtCaret?.let { element ->
            val expression = element.parentsWithSelf.firstIsInstanceOrNull<KtExpression>()
            val symbols = expression?.let { resolveSymbolsForHighlight(it) } ?: emptyList()
            symbols.map { normalizeToKey(it) }.toSet()
        } ?: emptySet()

        for (i in 0 until lineCount) {
            builder.append {
                for (element in getElementsAtLine(file, i)) {
                    try {
                        renderPsiElement(element, elementAtCaret, caretKeys)
                    } catch (_: Throwable) {
                        tokenType = TokenType.DEFAULT
                        bold = false
                        italic = false
                        append(element.text)
                    }
                }

                val document = file.fileDocument
                val lineStartOffset = document.getLineStartOffset(i)
                val lineEndOffset = document.getLineEndOffset(i)
                hints.filter { it.index in lineStartOffset .. lineEndOffset }.forEach { appendHint(it.copy(index = it.index - lineStartOffset)) }
            }
        }
    }

    return builder.lines
}
// Comments and strings are excluded so a caret inside them does not highlight the whole element.
private val nonSelectableCaretTokens: List<IElementType> = buildList {
    add(KtTokens.DOT)
    addAll(KtTokens.ALL_ASSIGNMENTS.types)
    addAll(KtTokens.COMMENTS.types)
    addAll(KtTokens.STRINGS.types)
    add(KtTokens.OPEN_QUOTE)
    add(KtTokens.CLOSING_QUOTE)
}

/**
 * Occurrence ranges (matching bracket, same-symbol identifiers) for the element at [offset].
 * Unlike [highlightCode] this resolves only identifiers with the same text, so it is cheap
 * enough to run on every caret move.
 */
fun occurrencesCode(file: KtFile, offset: Int): List<OccurrenceRange> {
    val target = file.findElementAtCaret(offset) ?: return emptyList()
    val targetRange = target.textRange.toOccurrence()

    matchingBracketFor(target)?.let { match ->
        return listOf(targetRange, match.textRange.toOccurrence()).sortedBy { it.start }
    }
    if (target.node.elementType != KtTokens.IDENTIFIER) {
        return if (target.node.elementType in bracketTokens) listOf(targetRange) else emptyList()
    }

    val targetText = target.text
    val ranges = ArrayList<OccurrenceRange>()
    analyze(file) {
        val expression = target.parentsWithSelf.firstIsInstanceOrNull<KtExpression>()
        val caretKeys = expression?.let { resolveSymbolsForHighlight(it) }
            ?.map { normalizeToKey(it) }?.toSet().orEmpty()

        var leaf: PsiElement? = file.findElementAt(0)
        while (leaf != null) {
            if (leaf.node?.elementType == KtTokens.IDENTIFIER && leaf.text == targetText) {
                val matches = leaf == target || caretKeys.isNotEmpty() && run {
                    val leafExpression = leaf.parentsWithSelf.firstIsInstanceOrNull<KtExpression>()
                    val symbols = leafExpression?.let { resolveSymbolsForHighlight(it) }.orEmpty()
                    symbols.any { normalizeToKey(it) in caretKeys }
                }
                if (matches) ranges += leaf.textRange.toOccurrence()
            }
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
    }
    if (ranges.isEmpty()) ranges += targetRange
    return ranges
}

private fun TextRange.toOccurrence() = OccurrenceRange(startOffset, endOffset)

private val bracketTokens: Set<IElementType> = setOf(
    KtTokens.LPAR, KtTokens.RPAR,
    KtTokens.LBRACE, KtTokens.RBRACE,
    KtTokens.LBRACKET, KtTokens.RBRACKET,
)

private fun matchingBracketFor(target: PsiElement): PsiElement? {
    if (target.node.elementType !in bracketTokens) return null
    val parent = target.parent ?: return null
    val other = when (target) {
        parent.firstChild -> parent.lastChild
        parent.lastChild -> parent.firstChild
        else -> null
    } ?: return null
    return other.takeIf { target.isOtherParenthesis(it) }
}

private fun PsiFile.findElementAtCaret(offset: Int): PsiElement? {
    if (offset < 0) return null
    val safeOffset = offset.coerceIn(0, textLength)
    return findElementAt(safeOffset).takeIfCaretSelectable()
        ?: if (safeOffset > 0) findElementAt(safeOffset - 1).takeIfCaretSelectable() else null
}

private fun PsiElement?.takeIfCaretSelectable() = takeIf {
    it !is PsiWhiteSpace && it?.node?.elementType !in nonSelectableCaretTokens
}

context(session: KaSession)
private fun LineBuilder.SpanBuilder.renderPsiElement(
    element: Element,
    elementAtCaret: PsiElement?,
    caretKeys: Set<KaSymbol>,
) {
    with(session) {
        val psi = element.psiElement
        val parent = psi.parent
        val text = element.text
        val elementType = psi.node.elementType

        tokenType = TokenType.DEFAULT
        bold = false
        italic = false
        colorOverride = null

        if (KtTokens.COMMENTS.contains(elementType) || psi.parentsWithSelf.any { it is PsiComment || it is KDoc }) {
            appendCommentSpans(text)
            return
        }

        when {
            psi is KtAnnotationEntry || parent is KtAnnotationEntry -> tokenType = TokenType.ANNOTATION
            elementType in KtTokens.KEYWORDS || elementType in KtTokens.SOFT_KEYWORDS -> tokenType = TokenType.KEYWORD
            elementType in KtTokens.STRINGS || elementType == KtTokens.OPEN_QUOTE || elementType == KtTokens.CLOSING_QUOTE -> tokenType = TokenType.STRING
            parent is KtConstantExpression && !isEnumConstant(parent) -> tokenType = TokenType.NUMERIC_LITERAL
            elementType == KtTokens.MINUS && psi.isUnaryNumericMinus() -> tokenType = TokenType.NUMERIC_LITERAL

            else -> {
                val resolution = tryResolveSymbols(parent, psi)

                val bestSymbol = pickBestSymbol(resolution.symbols)

                if (bestSymbol != null) {
                    computeSymbolStyle(bestSymbol)
                } else if (resolution.failed && isCalleeName(psi, parent)) {
                    tokenType = TokenType.FUNCTION
                } else if (parent is KtConstantExpression && isEnumConstant(parent)) {
                    tokenType = TokenType.PROPERTY_IDENTIFIER
                }
            }
        }

        if (shouldHighlight(psi, elementAtCaret, caretKeys)) {
            highlight = true
        }

        append(text)
    }
}

private val TaskMarkerRegex = Regex("""\b(TODO|FIXME|HACK|XXX|BUG)\b""")

private fun LineBuilder.SpanBuilder.appendCommentSpans(text: String) {
    val marker = TaskMarkerRegex.find(text)
    if (marker == null) {
        tokenType = TokenType.COMMENT
        append(text)
        return
    }
    if (marker.range.first > 0) {
        tokenType = TokenType.COMMENT
        append(text.substring(0, marker.range.first))
    }
    tokenType = TokenType.TODO_COMMENT
    append(text.substring(marker.range.first))
}

private val DslMarkerClassId = ClassId.topLevel(FqName("kotlin.DslMarker"))

context(session: KaSession)
private fun dslMarkerFqName(symbol: KaSymbol): String? {
    with(session) {
        val callable = symbol as? KaCallableSymbol ?: return null
        val receiver = callable.receiverType
        val owner = (callable.containingSymbol as? KaClassSymbol)?.defaultType
        for (type in listOfNotNull(receiver, owner)) {
            type.annotations.firstDslMarker()?.let { return it }
            (type.expandedSymbol)?.dslMarkerFqName(HashSet())?.let { return it }
        }
        return null
    }
}

context(session: KaSession)
private fun KaClassSymbol.dslMarkerFqName(visited: MutableSet<ClassId>): String? {
    with(session) {
        val id = classId
        if (id != null && !visited.add(id)) return null
        annotations.firstDslMarker()?.let { return it }
        for (superType in superTypes) {
            (superType.expandedSymbol)?.dslMarkerFqName(visited)?.let { return it }
        }
        return null
    }
}

context(session: KaSession)
private fun List<KaAnnotation>.firstDslMarker(): String? {
    with(session) {
        for (annotation in this@firstDslMarker) {
            val classId = annotation.classId ?: continue
            if (classId == DslMarkerClassId) continue
            val declaration = findClass(classId) ?: continue
            if (DslMarkerClassId in declaration.annotations) return classId.asFqNameString()
        }
        return null
    }
}

private fun PsiElement.isUnaryNumericMinus(): Boolean {
    val prefix = parent?.parent as? KtPrefixExpression ?: return false
    if (prefix.operationReference.node.elementType != KtTokens.MINUS) return false
    return prefix.baseExpression is KtConstantExpression
}

context(session: KaSession)
private fun shouldHighlight(
    current: PsiElement,
    target: PsiElement?,
    caretKeys: Set<KaSymbol>,
): Boolean {
    with(session) {
        if (target == null) return false

        if (current == target) return true

        if (current.isOtherParenthesis(target)) return true
        if (target.isOtherParenthesis(current)) return true

        if (current.node.elementType != target.node.elementType) return false
        // Different identifier text can never resolve to the same symbol; skip the costly resolve.
        if (current.node.elementType == KtTokens.IDENTIFIER && current.text != target.text) return false

        if (caretKeys.isEmpty()) return false

        val currentExpression = current.parentsWithSelf.firstIsInstanceOrNull<KtExpression>() ?: return false
        val currentSymbols = resolveSymbolsForHighlight(currentExpression)

        return currentSymbols.any { symbol ->
            normalizeToKey(symbol) in caretKeys
        }
    }
}

/**
 * Главная логика нормализации для сравнения.
 * Возвращает "Ключ", по которому символы считаются эквивалентными.
 */
context(session: KaSession)
private fun normalizeToKey(symbol: KaSymbol): KaSymbol {
    with(session) {
        return when (symbol) {
            // Конструктор -> ID класса
            is KaConstructorSymbol -> symbol.containingSymbol ?: symbol

            // Класс -> ID класса
            is KaClassSymbol -> {
                // Если это Companion Object, считаем его равным самому классу
                if (symbol.classKind == KaClassKind.COMPANION_OBJECT) symbol.containingSymbol ?: symbol
                else symbol
            }

            // Для остальных символов (функции, переменные) ключом является сам символ (equals/hashCode)
            else -> symbol
        }
    }
}

/**
 * What resolving one element gave us. [failed] means the Analysis API threw rather than simply not
 * finding anything, which is a different situation: the name is probably fine, we just cannot see it.
 */
private class SymbolResolution(val symbols: List<KaSymbol>, val failed: Boolean = false) {
    companion object {
        val EMPTY = SymbolResolution(emptyList())
        val FAILED = SymbolResolution(emptyList(), failed = true)
    }
}

context(session: KaSession)
private fun resolveSymbolsForHighlight(element: PsiElement, specificPsi: PsiElement? = null): List<KaSymbol> =
    tryResolveSymbols(element, specificPsi).symbols

context(session: KaSession)
private fun tryResolveSymbols(element: PsiElement, specificPsi: PsiElement? = null): SymbolResolution {
    with(session) {
        return when (element) {
            is KtSimpleNameExpression -> try {
                SymbolResolution(element.mainReference.resolveToSymbols().toList())
            } catch (_: Throwable) {
                SymbolResolution.FAILED
            }

            is KtNamedDeclaration -> {
                // Если указан specificPsi (например, идентификатор имени), проверяем, что это он
                if (specificPsi != null && element.nameIdentifier != specificPsi) return SymbolResolution.EMPTY
                try {
                    SymbolResolution(listOfNotNull(element.symbol))
                } catch (_: Throwable) {
                    SymbolResolution.FAILED
                }
            }

            else -> SymbolResolution.EMPTY
        }
    }
}

/** True when [psi] is the name being called in `name(...)`, so it can be coloured as a call blindly. */
private fun isCalleeName(psi: PsiElement, parent: PsiElement?): Boolean {
    val reference = parent as? KtSimpleNameExpression ?: return false
    if (reference.getReferencedNameElement() != psi) return false
    val call = reference.parent as? KtCallExpression ?: return false
    return call.calleeExpression == reference
}

// --- Остальные методы (Стилизация и утилиты) без изменений или с мелкими правками ---

private fun pickBestSymbol(symbols: Collection<KaSymbol>): KaSymbol? {
    if (symbols.isEmpty()) return null
    if (symbols.size == 1) return symbols.first()

    return symbols.firstOrNull { it is KaClassSymbol }
        ?: symbols.firstOrNull { it is KaConstructorSymbol }
        ?: symbols.firstOrNull { it is KaVariableSymbol }
        ?: symbols.firstOrNull { it is KaFunctionSymbol }
        ?: symbols.first()
}

context(session: KaSession)
private fun LineBuilder.SpanBuilder.computeSymbolStyle(symbol: KaSymbol) {
    with(session) {
        tokenType = when (symbol) {
            is KaPackageSymbol -> TokenType.DEFAULT
            is KaTypeAliasSymbol -> {
                symbol.expandedType.expandedSymbol?.let {
                    computeSymbolStyle(it)
                    tokenType
                } ?: TokenType.CLASS
            }

            is KaTypeParameterSymbol -> TokenType.PARAMETER
            is KaClassSymbol -> when (symbol.classKind) {
                KaClassKind.CLASS -> TokenType.CLASS
                KaClassKind.ENUM_CLASS -> TokenType.ENUM
                KaClassKind.INTERFACE -> TokenType.INTERFACE
                KaClassKind.ANNOTATION_CLASS -> TokenType.ANNOTATION
                KaClassKind.OBJECT, KaClassKind.COMPANION_OBJECT, KaClassKind.ANONYMOUS_OBJECT -> TokenType.OBJECT
            }

            is KaVariableSymbol -> when (symbol) {
                is KaEnumEntrySymbol -> TokenType.PROPERTY_IDENTIFIER
                is KaBackingFieldSymbol, is KaJavaFieldSymbol -> TokenType.FIELD
                is KaLocalVariableSymbol -> TokenType.VARIABLE
                is KaValueParameterSymbol -> TokenType.PARAMETER
                is KaKotlinPropertySymbol, is KaSyntheticJavaPropertySymbol -> TokenType.PROPERTY_IDENTIFIER
                else -> TokenType.VARIABLE
            }

            is KaFunctionSymbol -> when (symbol) {
                is KaConstructorSymbol -> {
                    (symbol.containingSymbol as? KaClassSymbol)?.let {
                        when (it.classKind) {
                            KaClassKind.ANNOTATION_CLASS -> TokenType.ANNOTATION
                            KaClassKind.INTERFACE -> TokenType.INTERFACE
                            else -> TokenType.CLASS
                        }
                    } ?: TokenType.CLASS
                }
                is KaNamedFunctionSymbol -> {
                    if (symbol.isExtension) TokenType.EXTENSION_RECEIVER
                    else if (symbol.location == KaSymbolLocation.TOP_LEVEL) TokenType.TOP_LEVEL
                    else if (symbol.location == KaSymbolLocation.CLASS) TokenType.METHOD
                    else TokenType.FUNCTION
                }

                else -> TokenType.FUNCTION
            }

            else -> TokenType.DEFAULT
        }
        applyDslMarker(symbol)
        applyModifiers(symbol)
    }
}

context(session: KaSession)
private fun LineBuilder.SpanBuilder.applyDslMarker(symbol: KaSymbol) {
    with(session) {
        val marker = runCatching { dslMarkerFqName(symbol) }.getOrNull() ?: return
        tokenType = TokenType.DSL_MARKER
        colorOverride = HighlightTheme.dslMarkerArgb(marker)
    }
}

context(session: KaSession)
private fun LineBuilder.SpanBuilder.applyModifiers(symbol: KaSymbol) {
    with(session) {
        if (symbol is KaCallableSymbol) {
            if (symbol.location == KaSymbolLocation.TOP_LEVEL && (symbol is KaNamedFunctionSymbol && symbol.isStatic)) {
                italic = true
            }
        }
    }
}

fun getElementsAtLine(psiFile: PsiFile, lineNumber: Int): List<Element> {
    val elements = mutableListOf<Element>()
    val document = psiFile.fileDocument

    if (lineNumber < 0 || lineNumber >= document.lineCount) return emptyList()

    val lineStartOffset = document.getLineStartOffset(lineNumber)
    val lineEndOffset = document.getLineEndOffset(lineNumber)
    val lineRange = TextRange(lineStartOffset, lineEndOffset)

    var currentElement: PsiElement? = psiFile.findElementAt(lineStartOffset)
    while (currentElement != null && currentElement.textRange.startOffset < lineEndOffset) {
        val elementRange = currentElement.textRange
        val intersection = elementRange.intersection(lineRange)
        elements.add(Element(currentElement, document.getText(intersection)))
        currentElement = PsiTreeUtil.nextLeaf(currentElement)
    }
    return elements
}

context(session: KaSession)
private fun isEnumConstant(element: KtElement): Boolean {
    with(session) {
        val expression = element as? KtReferenceExpression ?: return false
        val symbol = expression.mainReference.resolveToSymbol()
        return symbol is KaEnumEntrySymbol
    }
}

private fun PsiElement.isOtherParenthesis(other: PsiElement): Boolean {
    val matchingPairs = mapOf(
        KtTokens.LPAR to KtTokens.RPAR,
        KtTokens.LBRACE to KtTokens.RBRACE,
        KtTokens.LBRACKET to KtTokens.RBRACKET
    )
    val thisType = this.node.elementType
    val otherType = other.node.elementType

    if (matchingPairs[thisType] != otherType && matchingPairs[otherType] != thisType) return false
    val commonParent = PsiTreeUtil.findCommonParent(this, other) ?: return false
    return commonParent.firstChild == this && commonParent.lastChild == other ||
            commonParent.firstChild == other && commonParent.lastChild == this
}

class Element(val psiElement: PsiElement, val text: String)
