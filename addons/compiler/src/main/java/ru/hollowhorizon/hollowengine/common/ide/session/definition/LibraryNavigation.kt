package ru.hollowhorizon.hollowengine.common.ide.session.definition

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.asJava.toLightElements
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import ru.hollowhorizon.hollowengine.common.scripting.ide.DefinitionLocation
import ru.hollowhorizon.hollowengine.common.scripting.ide.SourceDocumentPath
import java.nio.file.Path

/**
 * Opens declarations that live outside the analyzed files.
 * Shared by the Kotlin and Java analyzers, which see the same libraries.
 */
internal class LibraryNavigation(
    private val project: Project,
    binaryRoots: List<Path>,
) {
    private val matcher = SourceDeclarationMatcher(project)
    private val decompiler = CfrDefinitionDecompiler()
    private val sources = LibrarySourceRepository(matcher, binaryRoots)

    fun definitionFor(element: PsiElement): DefinitionLocation? {
        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile
        if (virtualFile?.extension != "class" && !file.name.endsWith(".class")) {
            val offset = (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.startOffset
                ?: (element as? KtNamedDeclaration)?.nameIdentifier?.textRange?.startOffset
                ?: element.textRange.startOffset
            if (virtualFile == null || virtualFile.fileSystem.protocol != "file" && "!/" !in virtualFile.path) {
                return DefinitionLocation(file.name, offset)
            }
            return DefinitionLocation(SourceDocumentPath.ofLibraryFile(virtualFile.path).path, offset, file.text, true)
        }
        val target = SourceDeclarationIdentity.from(element) ?: return null
        val packageName = when (file) {
            is KtFile -> file.packageFqName.asString()
            is PsiClassOwner -> file.packageName
            else -> ""
        }
        sources.find(target, virtualFile, packageName)?.let { return it }
        val jvmDeclarations = if (element is KtElement) element.toLightElements() else listOf(element)
        for (declaration in jvmDeclarations) {
            val jvmTarget = SourceDeclarationIdentity.from(declaration) ?: continue
            val binary = declaration.containingFile?.virtualFile ?: virtualFile ?: continue
            decompiler.decompile(binary, jvmTarget, matcher)?.let { return it }
        }
        if (element is KtElement && virtualFile != null) {
            kotlinJvmFunction(virtualFile, target)?.let { (binary, jvmTarget) ->
                decompiler.decompile(binary, jvmTarget, matcher)?.let { return it }
            }
        }
        return null
    }

    /** Decompiles the class a [SourceDocumentPath.Decompiled] document was made from. */
    fun decompiledText(document: SourceDocumentPath.Decompiled): String? {
        val name = document.internalName.replace('/', '.')
        val psiClass =
            JavaPsiFacade.getInstance(project).findClass(name, GlobalSearchScope.allScope(project)) ?: return null
        return psiClass.containingFile?.virtualFile?.let(decompiler::text)
    }
}
