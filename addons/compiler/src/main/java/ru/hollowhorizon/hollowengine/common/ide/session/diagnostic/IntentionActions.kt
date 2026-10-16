package ru.hollowhorizon.hollowengine.common.ide.session.diagnostic

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import ru.hollowhorizon.hollowengine.common.ide.session.ScriptingAnalyzerImpl
import ru.hollowhorizon.hollowengine.common.ide.session.completion.createCompletions
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionItem
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionSink
import ru.hollowhorizon.hollowengine.common.scripting.ide.DiagnosticFix
import ru.hollowhorizon.hollowengine.common.scripting.ide.TextEdit

/**
 * Actions at [offset] beyond the fixes of the diagnostics there, which the editor already holds:
 * importing an unresolved name and removing every unused import from anywhere in the file.
 */
internal fun ScriptingAnalyzerImpl.intentionActions(file: KtFile, offset: Int): List<DiagnosticFix> {
    val caret = offset.coerceIn(0, file.textLength)
    val actions = ArrayList<DiagnosticFix>()
    val unusedImports = unusedImportDiagnostics(file).flatMap { it.fixes.take(1) }.flatMap { it.edits }
    if (unusedImports.isNotEmpty()) {
        actions += DiagnosticFix(
            "hollowengine.gui.ide.editor.fix.remove_unused_imports", unusedImports,
            listOf(unusedImports.size.toString()),
        )
    }
    val expression =
        sequenceOf(caret, caret - 1).filter { it in 0 until file.textLength }.mapNotNull(file::findElementAt)
            .flatMap { it.parentsWithSelf }.filterIsInstance<KtSimpleNameExpression>().firstOrNull()
            ?: return actions.distinct()
    val unresolved = analyze(file) { expression.mainReference.resolveToSymbols().isEmpty() }
    if (!unresolved) return actions.distinct()
    val name = expression.getReferencedName()
    createCompletions(file, expression.textRange.endOffset, CompletionSink { items ->
        items.filterIsInstance<CompletionItem.Declaration>().filter { it.import && it.name == name }
            .mapNotNull { it.fqName }.distinct().forEach { fqName ->
                actions += DiagnosticFix(
                    "hollowengine.gui.ide.editor.fix.import_declaration",
                    listOf(file.importEdit(fqName)), listOf(fqName),
                )
            }
        true
    })
    return actions.distinct()
}

/** Insert a sorted import without rewriting annotations, comments or the body. */
private fun KtFile.importEdit(fqName: String): TextEdit {
    val imports = importDirectives
    val following = imports.firstOrNull { it.importedFqName?.asString().orEmpty() > fqName }
    if (following != null) {
        val offset = following.textRange.startOffset
        return TextEdit(offset, offset, "import $fqName\n")
    }
    val last = imports.lastOrNull()
    if (last != null) {
        val offset = last.textRange.endOffset
        return TextEdit(offset, offset, "\nimport $fqName")
    }
    val headerEnd = maxOf(packageDirective?.textRange?.endOffset ?: 0, fileAnnotationList?.textRange?.endOffset ?: 0)
    val firstDeclaration = declarations.firstOrNull()?.textRange?.startOffset ?: textLength
    val offset = maxOf(headerEnd, firstDeclaration)
    val prefix = if (offset > 0 && text[offset - 1] != '\n') "\n\n" else ""
    return TextEdit(offset, offset, "${prefix}import $fqName\n\n")
}
