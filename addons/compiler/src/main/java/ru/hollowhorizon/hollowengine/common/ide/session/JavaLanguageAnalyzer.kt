package ru.hollowhorizon.hollowengine.common.ide.session

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil
import ru.hollowhorizon.hollowengine.common.ide.session.definition.definitionForPsi
import ru.hollowhorizon.hollowengine.common.scripting.ide.DefinitionLocation
import ru.hollowhorizon.hollowengine.common.scripting.ide.Diagnostic
import ru.hollowhorizon.hollowengine.common.scripting.ide.HoverInfo
import ru.hollowhorizon.hollowengine.common.scripting.ide.JavaScriptingAnalyzer
import ru.hollowhorizon.hollowengine.common.scripting.ide.OccurrenceRange
import ru.hollowhorizon.hollowengine.common.scripting.ide.Position
import ru.hollowhorizon.hollowengine.common.scripting.ide.Range
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptingAnalyzer
import ru.hollowhorizon.hollowengine.common.scripting.ide.Severity
import ru.hollowhorizon.hollowengine.common.scripting.ide.TextLine
import ru.hollowhorizon.hollowengine.common.scripting.ide.TokenType

/** Java PSI resolves calls using their receiver and arguments, including constructors and generics. */
internal class JavaLanguageAnalyzer(private val owner: ScriptingAnalyzerImpl) : ScriptingAnalyzer {
    private var cachedName: String? = null
    private var cachedText: String? = null
    private var cachedFile: PsiJavaFile? = null

    private fun file(name: String, text: String): PsiJavaFile {
        if (cachedName == name && cachedText == text) return requireNotNull(cachedFile)
        val file = PsiFileFactory.getInstance(owner.project)
            .createFileFromText(name, JavaFileType.INSTANCE, text) as PsiJavaFile
        cachedFile?.let(owner.projectStructureProvider::removeModule)
        owner.projectStructureProvider.setLibraryContext(file)
        cachedName = name
        cachedText = text
        cachedFile = file
        return file
    }

    override fun definition(name: String, text: String, offset: Int): DefinitionLocation? = synchronized(owner) {
        val file = file(name, text)
        val element = file.referenceAt(offset)?.resolvedTarget() ?: return@synchronized null
        if (element.containingFile == file) {
            DefinitionLocation(name, element.nameOffset())
        } else owner.definitionForPsi(element)
    }

    override fun hover(name: String, text: String, offset: Int): HoverInfo? = synchronized(owner) {
        val reference = file(name, text).referenceAt(offset) ?: return@synchronized null
        val target = reference.resolvedTarget() ?: return@synchronized null
        val range = reference.referenceNameElement?.textRange ?: reference.textRange
        HoverInfo(range.startOffset, range.endOffset, target.presentation())
    }

    override fun occurrences(name: String, text: String, offset: Int): List<OccurrenceRange> = synchronized(owner) {
        val file = file(name, text)
        val leaf = file.findElementAt(offset.coerceIn(0, (text.length - 1).coerceAtLeast(0)))
        val target = file.referenceAt(offset)?.resolvedTarget() ?: (leaf?.parent as? PsiNameIdentifierOwner)
            ?: return@synchronized emptyList()
        buildList {
            if (target.containingFile == file) {
                (target as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.let { add(OccurrenceRange(it.startOffset, it.endOffset)) }
            }
            PsiTreeUtil.collectElementsOfType(file, PsiJavaCodeReferenceElement::class.java).forEach { reference ->
                if (reference.resolvedTarget() == target) reference.referenceNameElement?.textRange?.let {
                    add(OccurrenceRange(it.startOffset, it.endOffset))
                }
            }
        }.distinct()
    }

    override fun highlight(name: String, text: String, offset: Int): List<TextLine> = synchronized(owner) {
        val file = file(name, text)
        var position = 0
        JavaScriptingAnalyzer.highlight(name, text, offset).map { line ->
            val spans = line.spans.map { (part, style) ->
                val leaf = file.findElementAt(position)
                position += part.length
                val identifier = leaf as? PsiIdentifier
                val target = (identifier?.parent as? PsiJavaCodeReferenceElement)?.resolve()
                    ?: (identifier?.parent as? PsiNameIdentifierOwner)
                part to (target?.tokenType()?.let { style.copy(color = it) } ?: style)
            }
            position++
            TextLine(spans, line.hints)
        }
    }

    override fun diagnostic(name: String, text: String): List<Diagnostic> = synchronized(owner) {
        PsiTreeUtil.collectElementsOfType(file(name, text), PsiErrorElement::class.java).map { error ->
            Diagnostic(Range(Position.at(text, error.textRange.startOffset), Position.at(text, error.textRange.endOffset)),
                Severity.ERROR, error.errorDescription)
        }
    }

    private fun PsiJavaFile.referenceAt(offset: Int): PsiJavaCodeReferenceElement? {
        return sequenceOf(offset, offset - 1).filter { it in 0 until textLength }
            .mapNotNull(::findElementAt)
            .flatMap { generateSequence(it) { element -> element.parent } }
            .filterIsInstance<PsiJavaCodeReferenceElement>().firstOrNull { reference ->
                reference.referenceNameElement?.textRange?.let { offset in it.startOffset..it.endOffset } == true
            }
    }
}

private fun PsiJavaCodeReferenceElement.resolvedTarget(): PsiElement? {
    val creation = generateSequence(parent) { it.parent }.filterIsInstance<PsiNewExpression>().firstOrNull()
    if (creation?.classReference == this) creation.resolveConstructor()?.let { return it }
    return resolve()
}

private fun PsiElement.nameOffset(): Int = (this as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.startOffset
    ?: textRange.startOffset

private fun PsiElement.tokenType(): TokenType? = when (this) {
    is PsiClass -> when { isAnnotationType -> TokenType.ANNOTATION; isEnum -> TokenType.ENUM; isInterface -> TokenType.INTERFACE; else -> TokenType.CLASS }
    is PsiMethod -> if (isConstructor) TokenType.CLASS else TokenType.METHOD
    is PsiField -> TokenType.FIELD
    is PsiParameter -> TokenType.PARAMETER
    is PsiVariable -> TokenType.VARIABLE
    else -> null
}

private fun PsiElement.presentation(): String = when (this) {
    is PsiMethod -> buildString {
        returnType?.let { append(it.presentableText).append(' ') }
        append(containingClass?.qualifiedName?.let { "$it." }.orEmpty()).append(name)
        append(parameterList.parameters.joinToString(", ", "(", ")") { "${it.type.presentableText} ${it.name}" })
    }
    is PsiClass -> qualifiedName ?: name.orEmpty()
    is PsiVariable -> "${type.presentableText} $name"
    else -> text
}
