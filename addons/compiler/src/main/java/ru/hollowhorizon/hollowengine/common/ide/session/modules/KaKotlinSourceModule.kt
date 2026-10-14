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

/** An opened Kotlin source is compiled as a file in its library, rather than as a script. */
@OptIn(KaExperimentalApi::class)
internal class KaKotlinSourceModule(
    file: KtFile,
    override val directRegularDependencies: List<KaModule>,
    override val directFriendDependencies: List<KaModule>,
) : KaSourceModule, KaModuleBase() {
    override val name: String = file.name
    override val project: Project = file.project
    override val psiRoots: List<PsiFileSystemItem> = listOf(file)
    override val baseContentScope: GlobalSearchScope = GlobalSearchScope.fileScope(file)
    override val directDependsOnDependencies: List<KaModule> = emptyList()
    override val languageVersionSettings: LanguageVersionSettings = HollowEngineLanguageSettings.INSTANCE
    override val targetPlatform: TargetPlatform = JvmPlatforms.defaultJvmPlatform
}
