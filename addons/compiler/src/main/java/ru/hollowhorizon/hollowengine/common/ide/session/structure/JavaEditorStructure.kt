package ru.hollowhorizon.hollowengine.common.ide.session.structure

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import ru.hollowhorizon.hollowengine.common.ide.session.ScriptingAnalyzerImpl
import ru.hollowhorizon.hollowengine.common.ide.session.definition.definitionForPsi
import ru.hollowhorizon.hollowengine.common.scripting.ide.CodeFold
import ru.hollowhorizon.hollowengine.common.scripting.ide.EditorStructure
import ru.hollowhorizon.hollowengine.common.scripting.ide.GutterMarker
import ru.hollowhorizon.hollowengine.common.scripting.ide.GutterMarkerKind
import ru.hollowhorizon.hollowengine.common.scripting.ide.GutterNavigationTarget

internal fun ScriptingAnalyzerImpl.javaEditorStructure(file: PsiJavaFile): EditorStructure {
    val folds = ArrayList<CodeFold>()
    PsiTreeUtil.processElements(file) { element ->
        when (element) {
            is PsiCodeBlock -> element.fold("{ … }")?.let(folds::add)
            is PsiClass -> element.lBrace?.let { brace ->
                if ('\n' in element.text) folds += CodeFold(brace.textOffset, element.textRange.endOffset)
            }
            is PsiComment -> element.fold(if (element.text.startsWith("/**")) "/**…*/" else "/*…*/")?.let(folds::add)
        }
        true
    }
    file.importList?.takeIf { it.allImportStatements.isNotEmpty() }?.let { imports ->
        val first = imports.allImportStatements.first()
        first.importReference?.let { reference ->
            folds += CodeFold(first.textOffset, imports.allImportStatements.last().textRange.endOffset, "…",
                prefixLength = reference.textRange.startOffset - first.textOffset, collapsedByDefault = true)
        }
    }
    val markers = PsiTreeUtil.collectElementsOfType(file, PsiMethod::class.java).flatMap { method ->
        buildList {
            val targets = method.findSuperMethods().mapNotNull { base ->
                definitionForPsi(base)?.let { GutterNavigationTarget("${base.containingClass?.qualifiedName}.${base.name}", it) }
            }.distinctBy { it.location.path to it.location.offset }
            if (targets.isNotEmpty()) add(GutterMarker(method.nameIdentifier?.textOffset ?: method.textOffset,
                GutterMarkerKind.OVERRIDE, targets))
            if (method.modifierList.findAnnotation("ru.hollowhorizon.hollowengine.common.events.SubscribeEvent") != null) {
                add(GutterMarker(method.nameIdentifier?.textOffset ?: method.textOffset, GutterMarkerKind.EVENT))
            }
        }
    }
    return EditorStructure(folds, markers)
}
