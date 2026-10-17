package ru.hollowhorizon.hollowengine.common.ide.session.completion

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtToken
import ru.hollowhorizon.hollowengine.common.scripting.ide.completionMatches

internal const val COMPLETION_FAKE_IDENTIFIER = "TheOneWhoPurifies"

internal fun matchesPrefix(prefix: String?, item: String): Boolean {
    return completionMatches(prefix, item)
}

val PsiElement.tokenType: KtToken?
    get() = node.elementType as? KtToken
