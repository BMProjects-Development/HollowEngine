package ru.hollowhorizon.hollowengine.common.ide.session

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProvider
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.packages.KotlinStandalonePackageProvider
import org.jetbrains.kotlin.psi.KtFile

/**
 * Knows packages declared by scripts open in the analyzer.
 */
class HollowPackageProviderFactory(
    private val project: Project,
    private val scripts: ProjectStructureProviderImpl,
) : KotlinPackageProviderFactory {
    override fun createPackageProvider(searchScope: GlobalSearchScope): KotlinPackageProvider {
        val packages = scripts.allSourceFiles.mapNotNullTo(HashSet()) { file ->
            (file as? KtFile)?.takeIf { it.virtualFile?.let(searchScope::contains) == true }?.packageFqName
        }
        return KotlinStandalonePackageProvider(project, searchScope, packages)
    }
}
