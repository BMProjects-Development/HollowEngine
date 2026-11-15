package ru.hollowhorizon.hollowengine.common.ide.session.inlays


import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.StubBasedPsiElement
import org.jetbrains.kotlin.analysis.api.KaContextParameterApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.resolveToCall
import org.jetbrains.kotlin.analysis.api.resolution.KaCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaSuccessCallInfo
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.psi.*
import ru.hollowhorizon.hollowengine.common.ide.session.completion.util.renderVerbose
import ru.hollowhorizon.hollowengine.common.scripting.ide.InlayHint
import ru.hollowhorizon.hollowengine.common.scripting.ide.InlayTags
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptAnalysisProject

val KtNamedDeclaration.isSingleUnderscore: Boolean
    get() {
        if (this is StubBasedPsiElement<*> && this.stub != null) return false
        return nameIdentifier?.text == "_"
    }


context(session: KaSession)
private fun PsiElement.determineType(): KaType? =
    with(session) {
        when (this@determineType) {
            is KtNamedFunction -> symbol.returnType
            is KtCallExpression -> expressionType
            is KtParameter if isLambdaParameter && typeReference == null -> returnType
            is KtDestructuringDeclarationEntry if !isSingleUnderscore -> expressionType
            is KtProperty -> returnType.takeIf { this !is KaErrorType }
            else -> null
        }
    }

@Suppress("ReturnCount")
context(session: KaSession)
private fun PsiElement.hintBuilder(kind: InlayKind, label: String? = null): InlayHint? {
    val element = when (this) {
        is KtFunction -> this.valueParameterList!!.originalElement
        is PsiNameIdentifierOwner -> this.nameIdentifier
        else -> this
    } ?: return null

    val hint = when (kind) {
        InlayKind.ParameterHint ->
            InlayHint(element.textRange.startOffset, "$label=", tags = listOf(InlayTags.PARAMETER))

        else ->
            this.determineType()?.let {
                InlayHint(
                    element.textRange.endOffset,
                    ": " + with(session) { it.renderVerbose() },
                    tags = listOf(InlayTags.TYPE),
                )
            } ?: return null
    }
    return hint
}

@Suppress("ReturnCount")
context(session: KaSession)
private fun callableArgNameHints(
    acc: MutableList<InlayHint>,
    callInfo: KaCallInfo,
): Unit = with(session) {
    val successfulCallInfo = callInfo as? KaSuccessCallInfo ?: return

    val functionCall = successfulCallInfo.call as? KaFunctionCall<*> ?: return

    for ((argExpression, variableSignature) in functionCall.argumentMapping) {

        val valueArgument = argExpression.parent as? KtValueArgument ?: continue

        // Правила фильтрации (когда подсказку показывать НЕ надо)

        // Если аргумент уже именован явно: foo(param = 1)
        if (valueArgument.isNamed()) continue

        // Если аргумент - это лямбда (особенно в качестве последнего аргумента): list.forEach { ... }
        if (valueArgument is KtLambdaArgument) continue

        val paramName = variableSignature.symbol.name.asString()

        // Если имя передаваемой переменной совпадает с именем параметра
        // Пример: fun setAlpha(alpha: Float). Вызов: setAlpha(alpha).
        if (argExpression is KtNameReferenceExpression && argExpression.getReferencedName() == paramName) {
            continue
        }

        val hint = argExpression.hintBuilder(InlayKind.ParameterHint, paramName)
        if (hint != null) {
            acc.add(hint)
        }
    }
}

context(session: KaSession)
private fun lambdaValueParamHints(
    acc: MutableList<InlayHint>,
    node: KtLambdaArgument,
) {

    val params = node.getLambdaExpression()!!.valueParameters

    // Подсказки нужны только для деструктурируемых параметров
    // Например: Map.forEach { (k, v) -> _ }
    // Здесь (k, v) станет (k: hint, v: hint): hint <- но вот эта подсказка уже не нужна
    params.singleOrNull()?.let {
        if (it.destructuringDeclaration != null) return
    }

    val hints = params.mapNotNull {
        it.hintBuilder(InlayKind.TypeHint)
    }
    acc.addAll(hints)
}

context(session: KaSession)
private fun destructuringVarHints(
    acc: MutableList<InlayHint>,
    node: KtDestructuringDeclaration,
) {
    val hints = node.entries.mapNotNull {
        it.hintBuilder(InlayKind.TypeHint)
    }
    acc.addAll(hints)
}

context(session: KaSession)
private fun declarationHint(
    acc: MutableList<InlayHint>,
    node: KtProperty,
) {
    // Если тип указан явно (т.е. `var value: String`), дублировать его не нужно
    if (node.typeReference != null) return

    val hint = node.hintBuilder(InlayKind.TypeHint) ?: return
    acc.add(hint)
}

context(session: KaSession)
private fun functionHint(
    acc: MutableList<InlayHint>,
    node: KtNamedFunction,
) {
    // Подсказки нужны только для expression функций, для функций с `{ ... }` тип указывается вручную
    if (!node.hasDeclaredReturnType() && !node.hasBlockBody()) {
        val hint = node.hintBuilder(InlayKind.TypeHint) ?: return
        acc.add(hint)
    }
}

@OptIn(KaContextParameterApi::class)
context(session: KaSession)
fun provideHints(file: KtFile, project: ScriptAnalysisProject? = null): List<InlayHint> {
    val res = mutableListOf<InlayHint>()
    res += resourceLocationHints(file, project)
    for (node in file.preOrderTraversal().asIterable()) {
        try {
            when (node) {
                is KtNamedFunction -> functionHint(res, node)
                is KtLambdaArgument -> lambdaValueParamHints(res, node)
                is KtCallExpression, is KtAnnotationEntry -> callableArgNameHints(res, node.resolveToCall() ?: continue)
                is KtDestructuringDeclaration -> destructuringVarHints(res, node)
                is KtProperty -> declarationHint(res, node)
            }
        } catch (e: Throwable) {
            // TODO: тут могут быть некоторые ошибки, из-за того, что JetBrains всё ещё не допилили implicitReceiver'ы в совокупности с context args
            //  KaFirResolver.toKaContextParameterValues() вызывает (Unexpected null receiver value for context argument)
        }
    }
    return res
}

fun PsiElement.preOrderTraversal(shouldTraverse: (PsiElement) -> Boolean = { true }): Sequence<PsiElement> {
    val root = this

    return sequence {
        if (shouldTraverse(root)) {
            yield(root)

            for (child in root.children) {
                if (shouldTraverse(child)) {
                    yieldAll(child.preOrderTraversal(shouldTraverse))
                }
            }
        }
    }
}

enum class InlayKind {
    TypeHint,
    ParameterHint,
}
