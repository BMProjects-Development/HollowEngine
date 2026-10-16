package ru.hollowhorizon.hollowengine.common.ide.session.modules

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaModuleBase
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import ru.hollowhorizon.hollowengine.common.ide.session.HollowEngineLanguageSettings

/**
 * Kotlin sources open in editor, compiled as ordinary files rather than as scripts. As a friend of
 * classpath they also see its `internal` declarations, which sources of a library use freely.
 */
@OptIn(KaExperimentalApi::class)
internal class KaKotlinSourceModule(
    override val project: Project,
    files: List<KtFile>,
    override val directRegularDependencies: List<KaModule>,
    override val directFriendDependencies: List<KaModule>,
) : KaSourceModule, KaModuleBase() {
    override val name: String = "Opened Kotlin sources"
    override val psiRoots: List<PsiFileSystemItem> = files
    override val baseContentScope: GlobalSearchScope =
        GlobalSearchScope.filesScope(project, files.map { it.virtualFile ?: it.viewProvider.virtualFile })
    override val directDependsOnDependencies: List<KaModule> = emptyList()
    override val languageVersionSettings: LanguageVersionSettings = HollowEngineLanguageSettings.INSTANCE
    override val targetPlatform: TargetPlatform = JvmPlatforms.defaultJvmPlatform
}
