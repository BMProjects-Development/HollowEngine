package ru.hollowhorizon.hollowengine.common.ide.session.structure

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaSuccessCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parents
import ru.hollowhorizon.hollowengine.client.HighlightTheme
import ru.hollowhorizon.hollowengine.common.scripting.ide.GutterMarker
import ru.hollowhorizon.hollowengine.common.scripting.ide.GutterMarkerKind

/**
 * The gutter markers of story code: stage and action calls, after which a restart continues, and checkpoints with the
 * calls that refer to them. Each checkpoint name of the file gets its own color, in the order the names first appear,
 * and `rewind` and `temporary` get the color of the checkpoint they lead to, so a call that names none still shows
 * which one it means.
 */
internal class StoryGutterMarkers(private val session: KaSession, file: KtFile) {
    private class Checkpoint(val owner: KtFunction?, val offset: Int, val name: String)

    private val checkpoints: List<Checkpoint> = file.collectDescendantsOfType<KtCallExpression>()
        .filter { it.calleeExpression?.text == CheckpointName.asString() && isStory(it, CheckpointName) }
        .mapNotNull { call -> literal(call.valueArguments.firstOrNull())?.let { Checkpoint(owner(call), call.textOffset, it) } }

    private val colors = LinkedHashMap<String, Int>().apply { checkpoints.forEach { getOrPut(it.name) { size } } }

    /** The marker of [call] at [expression], at offset 0 for the caller to place, or null when it is no story call. */
    fun markerFor(expression: KtExpression, call: KaFunctionCall<*>): GutterMarker? = with(session) {
        val symbol = call.symbol as? KaNamedFunctionSymbol ?: return null
        val callExpression = expression as? KtCallExpression
        if (symbol.callableId?.packageName == StoryPackage) when (symbol.name) {
            CheckpointName -> {
                val name = literal(callExpression?.valueArguments?.firstOrNull()) ?: "?"
                return GutterMarker(0, GutterMarkerKind.CHECKPOINT, listOf(name), color = color(name))
            }

            RewindName -> return rewindTarget(callExpression, expression)
                ?.let { GutterMarker(0, GutterMarkerKind.REWIND, listOf(it), color = color(it)) }
                ?: GutterMarker(0, GutterMarkerKind.REWIND_START)

            TemporaryName -> return temporaryTarget(callExpression, expression)
                ?.let { GutterMarker(0, GutterMarkerKind.TEMPORARY, listOf(it), color = color(it)) }
                ?: GutterMarker(0, GutterMarkerKind.TEMPORARY_OPEN)
        }
        if (StateClassId in symbol.annotations) return GutterMarker(0, GutterMarkerKind.STAGE, listOf(symbol.name.asString()))
        if ((ActionClassId in symbol.annotations || ActionAliasClassId in symbol.annotations) && isBoundary(expression)) {
            return GutterMarker(0, GutterMarkerKind.ACTION_CALL, listOf(symbol.name.asString()))
        }
        val receiver = call.partiallyAppliedSymbol.dispatchReceiver?.type
        if (symbol.name.asString() == "invoke" && receiver != null && StateClassId in receiver.annotations) {
            return GutterMarker(0, GutterMarkerKind.STAGE, listOf(callExpression?.calleeExpression?.text ?: "invoke"))
        }
        return null
    }

    /**
     * The offsets of the checkpoints the `rewind` or `temporary` call [call] leads to: those of the code it is written
     * in first, then the checkpoints of the same name elsewhere in the file, which another run of the story may pass.
     */
    fun checkpointsOf(call: KtCallExpression): List<Int> {
        val target = when {
            isStory(call, RewindName) -> rewindTarget(call, call)
            isStory(call, TemporaryName) -> temporaryTarget(call, call)
            else -> null
        } ?: return emptyList()
        val owner = owner(call)
        return checkpoints.filter { it.name == target }.sortedBy { if (it.owner == owner) 0 else 1 }.map { it.offset }
    }

    /** The checkpoint a `rewind` goes to: the one it names, or the last one before it in the same code. */
    private fun rewindTarget(call: KtCallExpression?, expression: KtExpression): String? = argument(call, "checkpoint", 0)
        ?: checkpoints.lastOrNull { it.owner == owner(expression) && it.offset < expression.textOffset }?.name

    /** The checkpoint a `temporary` stands until: the one it names, or the next one after it in the same code. */
    private fun temporaryTarget(call: KtCallExpression?, expression: KtExpression): String? = argument(call, "until", null)
        ?: checkpoints.firstOrNull { it.owner == owner(expression) && it.offset > expression.textOffset }?.name

    private fun color(name: String): Int = HighlightTheme.paletteArgb(colors.getOrPut(name) { colors.size })

    /**
     * Whether an action called at [expression] is a boundary: it is written in a stage or `@State` lambda itself, not
     * in a lambda inside it or in `catch` and `finally`, where it is an ordinary call.
     */
    private fun isBoundary(expression: KtExpression): Boolean = with(session) {
        val function = expression.parents.filterIsInstance<KtFunction>().firstOrNull() ?: return false
        val stage = when (function) {
            is KtNamedFunction -> StateClassId in function.symbol.annotations
            is KtFunctionLiteral -> isStageLambda(function)
            else -> false
        }
        stage && expression.parents.takeWhile { it != function }.none { it is KtCatchClause || it is KtFinallySection }
    }

    /** The code a checkpoint belongs to: the function or `@State` lambda it is written in, which is one run's. */
    private fun owner(element: PsiElement): KtFunction? = element.parents.filterIsInstance<KtFunction>()
        .firstOrNull { it is KtNamedFunction || it is KtFunctionLiteral && isStageLambda(it) }

    private fun isStageLambda(literal: KtFunctionLiteral): Boolean = with(session) {
        val lambda = literal.parent as? KtLambdaExpression ?: return false
        val call = lambda.getParentOfType<KtCallExpression>(true) ?: return false
        val resolved = (call.resolveToCall() as? KaSuccessCallInfo)?.call as? KaFunctionCall<*> ?: return false
        val parameter = resolved.argumentMapping[lambda] ?: return false
        StateClassId in parameter.returnType.annotations
    }

    private fun isStory(call: KtCallExpression, name: Name): Boolean = with(session) {
        val symbol = ((call.resolveToCall() as? KaSuccessCallInfo)?.call as? KaFunctionCall<*>)?.symbol as? KaNamedFunctionSymbol
        symbol?.callableId?.packageName == StoryPackage && symbol.name == name
    }

    /** The constant string passed for [parameter], by name or, when [position] is given, in that place. */
    private fun argument(call: KtCallExpression?, parameter: String, position: Int?): String? {
        val arguments = call?.valueArguments ?: return null
        val argument = arguments.firstOrNull { it.getArgumentName()?.asName?.asString() == parameter }
            ?: position?.let { arguments.getOrNull(it)?.takeIf { argument -> argument.getArgumentName() == null } }
        return literal(argument)
    }

    private fun literal(argument: ValueArgument?): String? {
        val template = argument?.getArgumentExpression() as? KtStringTemplateExpression ?: return null
        return (template.entries.singleOrNull() as? KtLiteralStringTemplateEntry)?.text
    }

    private companion object {
        val StoryPackage = FqName("ru.hollowhorizon.hollowengine.addons.narrative.story")
        val StateClassId = ClassId(StoryPackage, Name.identifier("State"))
        val ActionClassId = ClassId(StoryPackage, Name.identifier("Action"))
        val ActionAliasClassId = ClassId(StoryPackage, Name.identifier("ActionAlias"))
        val CheckpointName = Name.identifier("checkpoint")
        val RewindName = Name.identifier("rewind")
        val TemporaryName = Name.identifier("temporary")
    }
}
