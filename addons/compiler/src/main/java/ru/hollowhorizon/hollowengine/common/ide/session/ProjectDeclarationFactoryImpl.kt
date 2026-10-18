package ru.hollowhorizon.hollowengine.common.ide.session

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.impl.base.util.LibraryUtils
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinCompositeDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinFileBasedDeclarationProvider
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.StandaloneProjectFactory
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreProjectEnvironment
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import ru.hollowhorizon.hollowengine.common.ide.session.index.JavaClassNameIndex
import java.nio.file.Path

class SimpleDeclarationProviderFactory(
    private val projectEnvironment: KotlinCoreProjectEnvironment,
    private val builtins: Builtins,
    private val binaryRoots: List<Path>,
    private val projectStructureProvider: ProjectStructureProviderImpl,
) : KotlinDeclarationProviderFactory {
    private val sourceFiles: List<KtFile>
        get() = projectStructureProvider.allSourceFiles.filterIsInstance<KtFile>()

    private val virtualFiles = getVirtualFilesByRoots(binaryRoots, projectEnvironment)
    internal val javaClassNameIndex = JavaClassNameIndex(virtualFiles)

    init {
        Disposer.register(projectEnvironment.parentDisposable) {
            dispose()
        }
    }

    private val binaryFactory = KotlinStandaloneDeclarationProviderFactory(
        projectEnvironment.project,
        projectEnvironment.environment,
        sourceKtFiles = emptyList(), // Сюда ничего не передаем, исходники обрабатываем вручную ниже
        sharedBinaryRoots = virtualFiles,
        shouldBuildStubsForBinaryLibraries = true, // Важно для скорости и корректности
        skipBuiltins = true
    )

    /**
     * Classes that name [superName] among their supertypes, from the libraries and from the sources
     * that are open. Feeds [HollowDirectInheritorsProvider]; see it for why this is not simply
     * `KotlinStandaloneFirDirectInheritorsProvider`.
     */
    fun directInheritorCandidates(superName: Name): Set<KtClassOrObject> =
        binaryFactory.getDirectInheritorCandidates(superName) + sourceCandidates(superName)

    fun inheritableTypeAliases(superName: Name): Set<KtTypeAlias> =
        binaryFactory.getInheritableTypeAliases(superName)

    private fun sourceCandidates(superName: Name): List<KtClassOrObject> =
        sourceFiles.flatMap { file ->
            file.collectDescendantsOfType<KtClassOrObject>().filter { candidate ->
                candidate.superTypeListEntries.any { entry ->
                    entry.typeAsUserType?.referencedName == superName.asString()
                }
            }
        }

    override fun createDeclarationProvider(
        scope: GlobalSearchScope,
        contextualModule: KaModule?,
    ): KotlinDeclarationProvider {
        val providers = mutableListOf<KotlinDeclarationProvider>()

        sourceFiles.forEach { ktFile ->
            if (scope.contains(ktFile.virtualFile)) {
                providers.add(KotlinFileBasedDeclarationProvider(ktFile))
            }
        }
        providers.add(binaryFactory.createDeclarationProvider(scope, contextualModule))
        providers.add(builtins.symbolProvider.createDeclarationProvider(scope, contextualModule))

        return KotlinCompositeDeclarationProvider.create(providers)
    }

    fun dispose() {
        virtualFiles.forEach { root ->
            LibraryUtils.getAllVirtualFilesFromRoot(root, includeRoot = true).forEach(::cleanPsiForVirtualFile)
        }
    }

    private fun cleanPsiForVirtualFile(file: VirtualFile) {
        file.kaModule = null
        removeFromPsiManager(file)
    }

    // Перегрузка removeFromPsiManager для VirtualFile (ранее была для KtFile)
    private fun removeFromPsiManager(virtualFile: VirtualFile) {
        val psiManager = PsiManager.getInstance(projectEnvironment.project) as? PsiManagerEx ?: return
        val fileManager = psiManager.fileManager
        fileManager.setViewProvider(virtualFile, null)
    }
}

fun getVirtualFilesByRoots(
    roots: List<Path>,
    kotlinCoreProjectEnvironment: KotlinCoreProjectEnvironment,
): List<VirtualFile> =
    StandaloneProjectFactory.getVirtualFilesForLibraryRoots(roots, kotlinCoreProjectEnvironment.environment).distinct()
