package ru.hollowhorizon.hollowengine.common.ide.session.completion

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import ru.hollowhorizon.hollowengine.common.ide.session.diagnostic.animationArgument
import ru.hollowhorizon.hollowengine.common.scripting.ide.AnimationClips
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionItem
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionItemTag
import ru.hollowhorizon.hollowengine.common.scripting.ide.declarationCompletionItem

internal fun animationNameCompletions(file: KtFile, offset: Int, clips: AnimationClips): List<CompletionItem>? {
    val element = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
    val template = element.getParentOfType<KtStringTemplateExpression>(false) ?: return null
    if (offset <= template.textRange.startOffset || offset >= template.textRange.endOffset) return null
    val call = when (val parent = template.parent) {
        is KtBinaryExpression -> parent
        is KtValueArgument -> parent.getParentOfType<KtCallExpression>(true)
        else -> null
    } ?: return null
    val argument = analyze(file) { animationArgument(call, clips)?.takeIf { it.expression == template } } ?: return null
    val prefix = file.text.substring(template.textRange.startOffset + 1, offset)
    return argument.names.filter { it.startsWith(prefix, ignoreCase = true) }.sorted().map { name ->
        declarationCompletionItem {
            show = name
            insert = name
            this.name = name
            tag = CompletionItemTag.PROPERTY
            fqName = null
            wordChars = "-.:/"
            clipOf = argument.model
        }
    }
}
