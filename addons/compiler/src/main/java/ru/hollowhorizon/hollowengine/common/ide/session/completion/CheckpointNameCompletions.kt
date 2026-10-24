package ru.hollowhorizon.hollowengine.common.ide.session.completion

import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionItem
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionItemTag
import ru.hollowhorizon.hollowengine.common.scripting.ide.declarationCompletionItem

/**
 * The checkpoints of [file] for the string a story names one in, `rewind("…")` or `temporary(until = "…")`, or null
 * when the caret at [offset] is not in such a string and the ordinary completion should run.
 */
internal fun checkpointNameCompletions(file: KtFile, offset: Int): List<CompletionItem>? {
    val element = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
    val template = element.getParentOfType<KtStringTemplateExpression>(false) ?: return null
    if (offset <= template.textRange.startOffset || offset >= template.textRange.endOffset) return null
    val argument = template.parent as? KtValueArgument ?: return null
    val call = argument.getParentOfType<KtCallExpression>(true) ?: return null
    val named = argument.getArgumentName()?.asName?.asString()
    val namesCheckpoint = when (call.calleeExpression?.text) {
        "rewind" -> named == "checkpoint" || named == null && call.valueArguments.firstOrNull() === argument
        "temporary" -> named == "until"
        else -> false
    }
    if (!namesCheckpoint) return null
    val prefix = file.text.substring(template.textRange.startOffset + 1, offset)
    return checkpointNames(file).filter { it.startsWith(prefix) }.map { name ->
        declarationCompletionItem {
            show = name
            insert = name
            this.name = name
            tag = CompletionItemTag.PROPERTY
            fqName = null
            wordChars = "-."
        }
    }
}

/** The names the checkpoints of [file] are given as constants. */
internal fun checkpointNames(file: KtFile): List<String> = file.collectDescendantsOfType<KtCallExpression>()
    .filter { it.calleeExpression?.text == "checkpoint" }
    .mapNotNull { call ->
        val template = call.valueArguments.firstOrNull()?.getArgumentExpression() as? KtStringTemplateExpression
        (template?.entries?.singleOrNull() as? KtLiteralStringTemplateEntry)?.text
    }
    .distinct()
