package ru.hollowhorizon.hollowengine.common.ide.session.diagnostic

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaSuccessCallInfo
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import ru.hollowhorizon.hollowengine.common.scripting.ide.AnimationClips
import ru.hollowhorizon.hollowengine.common.scripting.ide.Diagnostic
import ru.hollowhorizon.hollowengine.common.scripting.ide.Severity
import ru.hollowhorizon.hollowengine.common.utils.Spelling

private const val EDITOR = "ru.hollowhorizon.hollowengine.common.attachments.editor"
private val AnimationClassId = ClassId.topLevel(FqName("$EDITOR.EditorAnimation"))
private val ModelClassId = ClassId.topLevel(FqName("$EDITOR.EditorModel"))
private val NpcIdClassId = ClassId.topLevel(FqName("$EDITOR.EditorNpcId"))

internal fun animationNameDiagnostics(file: KtFile, clips: AnimationClips): List<Diagnostic> {
    val calls = PsiTreeUtil.collectElementsOfType(file, KtCallExpression::class.java)
    if (calls.isEmpty()) return emptyList()
    val document = file.fileDocument
    val diagnostics = ArrayList<Diagnostic>()
    analyze(file) {
        for (call in calls) {
            val argument = call.function()?.argumentWith(AnimationClassId) ?: continue
            val clip = argument.literal() ?: continue
            val qualified = call.parent as? KtQualifiedExpression
            val receiver = qualified?.takeIf { it.selectorExpression == call }?.receiverExpression ?: continue
            val names = modelOf(receiver, clips, 0)?.let(clips::of) ?: continue
            if (clip in names) continue
            diagnostics += Diagnostic(document.rangeOf(argument.textRange), Severity.WARNING, message(clip, names))
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

context(session: KaSession)
private fun KtCallExpression.function(): KaFunctionCall<*>? = with(session) {
    runCatching { (resolveToCall() as? KaSuccessCallInfo)?.call as? KaFunctionCall<*> }.getOrNull()
}

context(session: KaSession)
private fun KaFunctionCall<*>.argumentWith(annotation: ClassId): KtExpression? = with(session) {
    argumentMapping.entries.firstOrNull { (_, parameter) -> annotation in parameter.symbol.annotations }?.key
}

private const val MAX_HOPS = 4

context(session: KaSession)
private fun modelOf(expression: KtExpression, clips: AnimationClips, hops: Int): String? = with(session) {
    if (hops > MAX_HOPS) return null
    when (val value = KtPsiUtil.safeDeparenthesize(expression)) {
        is KtCallExpression -> made(value, clips)
        is KtQualifiedExpression -> (value.selectorExpression as? KtCallExpression)?.let { made(it, clips) }
        is KtNameReferenceExpression -> {
            val property = runCatching { value.mainReference.resolveToSymbol()?.psi }.getOrNull() as? KtProperty
            property?.takeUnless { it.isVar }?.initializer?.let { modelOf(it, clips, hops + 1) }
        }

        else -> null
    }
}

context(session: KaSession)
private fun made(call: KtCallExpression, clips: AnimationClips): String? {
    val function = call.function() ?: return null
    return function.argumentWith(ModelClassId)?.literal()?.takeIf { it.isNotEmpty() } ?: function.argumentWith(
        NpcIdClassId
    )?.literal()?.let(clips::modelOfNpc)
}

private fun KtExpression.literal(): String? {
    val template = this as? KtStringTemplateExpression ?: return null
    if (template.hasInterpolation()) return null
    val parts = template.entries.map { entry -> (entry as? KtLiteralStringTemplateEntry)?.text ?: return null }
    return parts.joinToString("")
}
