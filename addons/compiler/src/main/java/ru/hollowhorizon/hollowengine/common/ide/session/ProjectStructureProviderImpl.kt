package ru.hollowhorizon.hollowengine.common.ide.session

import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinProjectStructureProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaModuleBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaNotUnderContentRootModule
import org.jetbrains.kotlin.analysis.api.projectStructure.contextModule
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.KotlinStaticProjectStructureProvider
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import ru.hollowhorizon.hollowengine.common.ide.session.modules.KaJarLibraryModuleImpl
import ru.hollowhorizon.hollowengine.common.ide.session.modules.KaJdkLibraryModuleImpl
import ru.hollowhorizon.hollowengine.common.ide.session.modules.KaRekotLibraryModule

class ProjectStructureProviderImpl : KotlinStaticProjectStructureProvider(), KotlinProjectStructureProvider {
    private val libraryModules = mutableListOf<KaRekotLibraryModule>()
    private val sourceModules = linkedMapOf<KtFile, KaModule>()
    private val librarySourceOwners = mutableMapOf<String, KaModule>()

    override val allModules: List<KaModule>
        get() = libraryModules + sourceModules.values

    override val allSourceFiles: List<PsiFileSystemItem>
        get() = sourceModules.keys.toList()

    private val notUnderContentRootModules = mutableMapOf<Project, KaNotUnderContentRootModule>()

    override fun getNotUnderContentRootModule(project: Project): KaNotUnderContentRootModule {
        return notUnderContentRootModules.getOrPut(project) {
            KaNotUnderContentRootModuleImpl(
                name = "HollowEngineNotUnderContentRoot",
                directRegularDependencies = libraryModules.toList(),
                project = project,
            )
        }
    }

    override fun getImplementingModules(module: KaModule): List<KaModule> {
        error("Should not be called for jvm code")
    }

    override fun getModule(element: PsiElement, useSiteModule: KaModule?): KaModule {
        val containingFile = element.containingFile

        val virtualFile: VirtualFile = containingFile.virtualFile ?: containingFile.viewProvider.virtualFile
        virtualFile.kaModule?.let { return it }

        for (jarLibraryModule in libraryModules) {
            when (jarLibraryModule) {
                is KaJarLibraryModuleImpl -> {
                    if (jarLibraryModule.baseContentScope.contains(virtualFile)) {
                        return jarLibraryModule
                    }
                }
                is KaJdkLibraryModuleImpl -> {
                    if (jarLibraryModule.baseContentScope.contains(virtualFile)) {
                        return jarLibraryModule
                    }
                }
                else -> error("Unknown library module type: $jarLibraryModule")
            }
        }

        error("Module not found for $virtualFile, $containingFile, $element")
    }

    fun setModule(file: VirtualFile, module: KaModule) {
        file.kaModule = module
    }

    fun setModule(file: PsiFile, module: KaModule) {
        setModule(file.virtualFile ?: file.viewProvider.virtualFile, module)
        if (file is KtFile) {
            file.contextModule = module
            sourceModules[file] = module
        }
    }

    internal fun setLibraryContext(file: PsiFile) {
        setModule(file, getNotUnderContentRootModule(file.project))
    }

    internal fun registerLibrarySource(path: String, declaration: PsiElement) {
        librarySourceOwners[path] = getModule(declaration, null)
    }

    internal fun librarySourceOwner(path: String): KaModule? = librarySourceOwners[path]

    fun removeModule(file: PsiFile) {
        if (file is KtFile) {
            file.contextModule = null
            sourceModules.remove(file)
        }
        (file.virtualFile ?: file.viewProvider.virtualFile).kaModule = null
    }

    fun registerLibraryModule(module: KaRekotLibraryModule) {
        libraryModules += module
    }
}

private val module_KEY: Key<KaModule> = Key.create("module_KEY")
var VirtualFile.kaModule: KaModule?
    get() = getUserData(module_KEY)
    set(value) {
        putUserData(module_KEY, value)
    }

@OptIn(KaExperimentalApi::class)
private class KaNotUnderContentRootModuleImpl(
    override val name: String,
    override val directRegularDependencies: List<KaModule>,
    override val project: Project,
) : KaNotUnderContentRootModule, KaModuleBase() {
    override val directDependsOnDependencies: List<KaModule> = emptyList()
    override val directFriendDependencies: List<KaModule> = emptyList()
    override val targetPlatform: TargetPlatform = JvmPlatforms.defaultJvmPlatform
    override val baseContentScope: GlobalSearchScope = GlobalSearchScope.EMPTY_SCOPE
    override val moduleDescription: String = name
}
