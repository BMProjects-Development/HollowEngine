package ru.hollowhorizon.hollowengine.common.ide.session.diagnostic

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import ru.hollowhorizon.hollowengine.common.scripting.ide.AnimationClips
import ru.hollowhorizon.hollowengine.common.scripting.ide.Diagnostic
import ru.hollowhorizon.hollowengine.common.scripting.ide.Severity
import ru.hollowhorizon.hollowengine.common.utils.Spelling

internal fun animationNameDiagnostics(file: KtFile, clips: AnimationClips): List<Diagnostic> {
    val calls = PsiTreeUtil.collectElementsOfType(file, KtCallExpression::class.java) +
        PsiTreeUtil.collectElementsOfType(file, KtBinaryExpression::class.java).filter { it.operationToken == KtTokens.IDENTIFIER }
    if (calls.isEmpty()) return emptyList()
    val document = file.fileDocument
    val diagnostics = ArrayList<Diagnostic>()
    analyze(file) {
        for (call in calls) {
            val argument = animationArgument(call, clips) ?: continue
            val clip = argument.expression.literal() ?: continue
            if (clip in argument.names) continue
            diagnostics += Diagnostic(document.rangeOf(argument.expression.textRange), Severity.WARNING, message(clip, argument.names))
        }
    }
    return diagnostics
}

private fun message(clip: String, names: List<String>): String {
    if (names.isEmpty()) return "This model has no animations"
    val similar = Spelling.similar(clip, names)
    val missing = "This model has no animation \"$clip\""
    return if (similar.isEmpty()) missing else "$missing. Did you mean: ${similar.joinToString()}?"
}
