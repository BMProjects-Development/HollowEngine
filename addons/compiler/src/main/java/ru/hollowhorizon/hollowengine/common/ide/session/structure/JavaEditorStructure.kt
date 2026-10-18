package ru.hollowhorizon.hollowengine.common.ide.session.structure

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import ru.hollowhorizon.hollowengine.common.ide.session.definition.LibraryNavigation
import ru.hollowhorizon.hollowengine.common.scripting.ide.CodeFold
import ru.hollowhorizon.hollowengine.common.scripting.ide.EditorStructure
import ru.hollowhorizon.hollowengine.common.scripting.ide.GutterMarker
import ru.hollowhorizon.hollowengine.common.scripting.ide.GutterMarkerKind
import ru.hollowhorizon.hollowengine.common.scripting.ide.GutterNavigationTarget

internal fun javaEditorStructure(file: PsiJavaFile): EditorStructure {
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
            if (method.findSuperMethods().isNotEmpty()) add(GutterMarker(method.markerOffset, GutterMarkerKind.OVERRIDE))
            if (method.modifierList.findAnnotation("ru.hollowhorizon.hollowengine.common.events.SubscribeEvent") != null) {
                add(GutterMarker(method.markerOffset, GutterMarkerKind.EVENT))
            }
        }
    }
    return EditorStructure(folds, markers)
}

internal fun javaGutterTargets(file: PsiJavaFile, offset: Int, navigation: LibraryNavigation): List<GutterNavigationTarget> {
    val method = PsiTreeUtil.collectElementsOfType(file, PsiMethod::class.java).firstOrNull { it.markerOffset == offset }
        ?: return emptyList()
    return method.findSuperMethods().mapNotNull { base ->
        navigation.definitionFor(base)?.let { GutterNavigationTarget("${base.containingClass?.qualifiedName}.${base.name}", it) }
    }.distinctBy { it.location.path to it.location.offset }
}

private val PsiMethod.markerOffset: Int get() = nameIdentifier?.textOffset ?: textOffset
