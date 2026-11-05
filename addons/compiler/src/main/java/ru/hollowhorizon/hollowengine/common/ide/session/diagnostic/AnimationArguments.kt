package ru.hollowhorizon.hollowengine.common.ide.session.diagnostic

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaSuccessCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import ru.hollowhorizon.hollowengine.client.editor.GameAnimationClips
import ru.hollowhorizon.hollowengine.common.scripting.ide.AnimationClips
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient

private const val EDITOR = "ru.hollowhorizon.hollowengine.common.attachments.editor"
private val AnimationClassId = ClassId.topLevel(FqName("$EDITOR.EditorAnimation"))
private val ModelClassId = ClassId.topLevel(FqName("$EDITOR.EditorModel"))
private val NpcIdClassId = ClassId.topLevel(FqName("$EDITOR.EditorNpcId"))
private const val MAX_HOPS = 4

internal val editorClips: AnimationClips get() = if (isPhysicalClient) GameAnimationClips else AnimationClips.None

internal class AnimationArgument(val expression: KtExpression, val model: String, val names: List<String>)

/**
 * The argument naming a clip in [call], a call or an infix one such as `npc playOnce "wave"`, when the model of the
 * entity it is made on can be told.
 */
context(session: KaSession)
internal fun animationArgument(call: KtExpression, clips: AnimationClips): AnimationArgument? {
    val argument = call.function()?.argumentWith(AnimationClassId) ?: return null
    val receiver = when (call) {
        is KtBinaryExpression -> call.left
        else -> (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == call }?.receiverExpression
    } ?: return null
    val model = modelOf(receiver, clips, 0) ?: return null
    return AnimationArgument(argument, model, clips.of(model) ?: return null)
}

internal fun KtExpression.literal(): String? {
    val template = this as? KtStringTemplateExpression ?: return null
    if (template.hasInterpolation()) return null
    val parts = template.entries.map { entry -> (entry as? KtLiteralStringTemplateEntry)?.text ?: return null }
    return parts.joinToString("")
}

context(session: KaSession)
private fun KtExpression.function(): KaFunctionCall<*>? = with(session) {
    runCatching { (resolveToCall() as? KaSuccessCallInfo)?.call as? KaFunctionCall<*> }.getOrNull()
}

context(session: KaSession)
private fun KaFunctionCall<*>.argumentWith(annotation: ClassId): KtExpression? = with(session) {
    valueArgumentMapping.entries.firstOrNull { (_, parameter) -> annotation in parameter.symbol.annotations }?.key
}

/** The model the expression [expression] makes, following vals to what they were made by. */
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

/**
 * The model of the entity [call] gives back: the one it names, that of a live NPC it names, the one its builder sets,
 * or the one its parameter falls back to. A name given by an expression may be any, so it says nothing.
 */
context(session: KaSession)
private fun made(call: KtCallExpression, clips: AnimationClips): String? {
    val function = call.function() ?: return null
    val model = function.argumentWith(ModelClassId)
    val builder = model as? KtLambdaExpression
    if (model != null && builder == null && model.literal() != "") return model.literal()
    function.argumentWith(NpcIdClassId)?.takeIf { it.literal() != "" }?.let { id ->
        val known = id.literal()?.let(clips::modelOfNpc)
        if (known != null || builder == null) return known
    }
    val named = builder?.bodyExpression?.let { body ->
        PsiTreeUtil.collectElementsOfType(body, KtCallExpression::class.java)
            .filter { it.function()?.namesModel() == true }.maxByOrNull { it.textOffset }
    }
    return if (named != null) made(named, clips) else function.fallbackModel()
}

/** Whether a parameter of [this] names the model of what it makes, such as that of `Model(...)`. */
context(session: KaSession)
private fun KaFunctionCall<*>.namesModel(): Boolean = with(session) {
    symbol.valueParameters.any { ModelClassId in it.annotations }
}

/** What the parameter naming the model of [this] is declared to fall back to. */
context(session: KaSession)
private fun KaFunctionCall<*>.fallbackModel(): String? = with(session) {
    val parameter = symbol.valueParameters.firstOrNull { ModelClassId in it.annotations }
    val annotation = parameter?.annotations?.firstOrNull { it.classId == ModelClassId } ?: return null
    val value = annotation.arguments.firstOrNull { it.name.asString() == "default" }?.expression
    ((value as? KaAnnotationValue.ConstantValue)?.value?.value as? String)?.takeIf(String::isNotEmpty)
}
