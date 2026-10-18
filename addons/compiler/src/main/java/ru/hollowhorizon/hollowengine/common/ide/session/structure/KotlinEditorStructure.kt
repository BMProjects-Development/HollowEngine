package ru.hollowhorizon.hollowengine.common.ide.session.structure

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.analysis.api.resolution.KaFunctionCall
import org.jetbrains.kotlin.analysis.api.resolution.KaSuccessCallInfo
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.parentsWithSelf
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionProvider
import org.jetbrains.kotlin.scripting.resolve.KtFileScriptSource
import ru.hollowhorizon.hollowengine.common.ide.session.ScriptingAnalyzerImpl
import ru.hollowhorizon.hollowengine.common.ide.session.definition.definitionForSymbol
import ru.hollowhorizon.hollowengine.common.scripting.ide.*
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.baseClass

internal fun ScriptingAnalyzerImpl.editorStructure(file: KtFile): EditorStructure {
    val folds = file.collectDescendantsOfType<PsiElement>().mapNotNull { element ->
        when (element) {
            is KtBlockExpression -> element.takeIf { it.lBrace != null && it.rBrace != null }?.fold("{ … }")
            is KtClassBody -> element.takeIf { it.lBrace != null && it.rBrace != null }?.fold("{ … }")
            is KtLambdaExpression -> element.takeIf { it.functionLiteral.rBrace != null }?.fold("{ … }")
            is PsiComment, is KDoc -> element.fold(if (element.text.startsWith("/**")) "/**…*/" else "/*…*/")
            is KtStringTemplateExpression -> element.takeIf { it.text.startsWith("\"\"\"") }?.fold("\"\"\"…\"\"\"")
            is KtWhenExpression -> element.openBrace?.takeIf { '\n' in element.text }?.let {
                CodeFold(it.textOffset, element.textRange.endOffset)
            }

            else -> null
        }
    }.toMutableList()
    file.importList?.takeIf { it.imports.isNotEmpty() }?.let { imports ->
        val first = imports.imports.first()
        first.importedReference?.let { reference ->
            folds += CodeFold(
                first.textOffset,
                imports.imports.last().textRange.endOffset,
                "…",
                prefixLength = reference.textRange.startOffset - first.textOffset,
                collapsedByDefault = true
            )
        }
    }
    val markers = ArrayList<GutterMarker>()
    val runs = ArrayList<ScriptRunTarget>()
    analyze(file) {
        val definition = if (file.isScript()) project.getService(ScriptDefinitionProvider::class.java)
            ?.findDefinition(KtFileScriptSource(file)) else null
        val baseClass = definition?.compilationConfiguration?.get(ScriptCompilationConfiguration.baseClass)?.typeName
        val node = file.isScript() && baseClass == NodeScript::class.qualifiedName
        val attached = AttachClassId in file.symbol.annotations
        if (file.isScript() && baseClass == "kotlin.Any") runs += ScriptRunTarget(0, ScriptRunKind.PLAIN)
        if (node && !attached) runs += ScriptRunTarget(0, ScriptRunKind.NODE)
        file.collectDescendantsOfType<KtDeclaration>().forEach { declaration ->
            if (declaration !is KtNamedFunction && !((declaration is KtProperty || declaration is KtParameter) && declaration.hasModifier(
                    KtTokens.OVERRIDE_KEYWORD
                ))
            ) {
                return@forEach
            }
            val offset = declaration.textOffset
            val symbol = declaration.markerSymbol()
            if (symbol is KaNamedFunctionSymbol) {
                if (EventClassId in symbol.annotations) markers += GutterMarker(offset, GutterMarkerKind.EVENT)
                if (node && !attached && StateClassId in symbol.annotations && declaration is KtNamedFunction && declaration.parent == file.script?.blockExpression && symbol.isSuspend && symbol.valueParameters.isEmpty() && !symbol.isExtension && symbol.contextParameters.isEmpty() && declaration.bodyExpression != null) {
                    val annotation = symbol.annotations.firstOrNull { it.classId == StateClassId }
                    val value = annotation?.arguments?.firstOrNull { it.name.asString() == "name" }?.expression
                    val stateName =
                        ((value as? KaAnnotationValue.ConstantValue)?.value?.value as? String)?.takeIf(String::isNotBlank)
                            ?: symbol.name.asString()
                    runs += ScriptRunTarget(offset, ScriptRunKind.NODE, stateName)
                }
            }
            if (declaration.hasModifier(KtTokens.OVERRIDE_KEYWORD) && symbol is KaCallableSymbol && symbol.directlyOverriddenSymbols.any()) {
                markers += GutterMarker(offset, GutterMarkerKind.OVERRIDE)
                (declaration as? KtProperty)?.accessors?.forEach { accessor ->
                    markers += GutterMarker(accessor.textOffset, GutterMarkerKind.OVERRIDE)
                }
            }
        }
        val coroutineLines = HashSet<Int>()
        file.collectDescendantsOfType<KtExpression>().forEach { expression ->
            val offset = when (expression) {
                is KtCallExpression -> expression.calleeExpression?.textOffset
                is KtBinaryExpression -> expression.operationReference.textOffset
                is KtUnaryExpression -> expression.operationReference.textOffset
                is KtArrayAccessExpression -> expression.textOffset
                else -> null
            } ?: return@forEach
            val call = (expression.resolveToCall() as? KaSuccessCallInfo)?.call as? KaFunctionCall<*> ?: return@forEach
            if ((call.symbol as? KaNamedFunctionSymbol)?.isSuspend == true && coroutineLines.add(
                    file.text.lastIndexOf(
                        '\n', offset
                    ) + 1
                )
            ) {
                markers += GutterMarker(offset, GutterMarkerKind.COROUTINE)
            }
        }
    }
    return EditorStructure(folds.distinctBy { it.start to it.end }, markers.distinctBy { it.offset to it.kind }, runs)
}

/** The declarations an override marker at [offset] leads to; an accessor marker leads to the base accessors. */
internal fun ScriptingAnalyzerImpl.kotlinGutterTargets(file: KtFile, offset: Int): List<GutterNavigationTarget> {
    val owner = file.findElementAt(offset)?.parentsWithSelf?.firstOrNull { element ->
        (element is KtPropertyAccessor || element is KtCallableDeclaration) && element.textOffset == offset
    } ?: return emptyList()
    val accessor = owner as? KtPropertyAccessor
    val declaration = accessor?.property ?: owner as KtCallableDeclaration
    return analyze(file) {
        val symbol = declaration.markerSymbol() as? KaCallableSymbol ?: return@analyze emptyList()
        symbol.directlyOverriddenSymbols.mapNotNull { base ->
            val property = base as? KaPropertySymbol
            val target = when {
                accessor == null -> base
                accessor.isGetter -> property?.getter ?: base
                else -> property?.setter ?: base
            }
            definitionForSymbol(target)?.let { location ->
                GutterNavigationTarget(
                    base.callableId?.asSingleFqName()?.asString() ?: base.psi?.text.orEmpty(),
                    location
                )
            }
        }.distinctBy { it.location.path to it.location.offset }.toList()
    }
}

/** A constructor parameter declares its property through the symbol generated for it. */
context(session: KaSession)
private fun KtDeclaration.markerSymbol(): KaDeclarationSymbol? = with(session) {
    val declaration = this@markerSymbol
    if (declaration is KtParameter) (declaration.symbol as? KaValueParameterSymbol)?.generatedPrimaryConstructorProperty
    else declaration.symbol
}

internal fun PsiElement.fold(placeholder: String): CodeFold? =
    textRange.takeIf { '\n' in text }?.let { CodeFold(it.startOffset, it.endOffset, placeholder) }

private val AttachClassId =
    ClassId.topLevel(FqName("ru.hollowhorizon.hollowengine.common.scripting.annotations.Attach"))
private val StateClassId = ClassId.topLevel(FqName("ru.hollowhorizon.hollowengine.common.scripting.annotations.State"))
private val EventClassId = ClassId.topLevel(FqName("ru.hollowhorizon.hollowengine.common.events.SubscribeEvent"))
