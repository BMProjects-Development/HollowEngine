package ru.hollowhorizon.hollowengine.common.ide.session

import com.intellij.core.CoreApplicationEnvironment
import com.intellij.ide.highlighter.JavaClassFileType
import com.intellij.lang.LanguageExtensionPoint
import com.intellij.mock.MockApplication
import com.intellij.mock.MockProject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers
import com.intellij.openapi.util.Disposer
import com.intellij.psi.ClassFileViewProviderFactory
import com.intellij.psi.FileTypeFileViewProviders
import com.intellij.psi.compiled.ClassFileDecompilers
import com.intellij.psi.impl.compiled.ClassFileDecompiler
import com.intellij.psi.impl.compiled.ClassFileStubBuilder
import com.intellij.psi.impl.compiled.ClsDecompilerImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.BinaryFileStubBuilders
import org.jetbrains.kotlin.analysis.api.impl.base.util.LibraryUtils
import org.jetbrains.kotlin.analysis.api.permissions.KaAnalysisPermissionRegistry
import org.jetbrains.kotlin.analysis.api.platform.KotlinDeserializedDeclarationsOrigin
import org.jetbrains.kotlin.analysis.api.platform.KotlinMessageBusProvider
import org.jetbrains.kotlin.analysis.api.platform.KotlinPlatformSettings
import org.jetbrains.kotlin.analysis.api.platform.KotlinProjectMessageBusProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProviderMerger
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDirectInheritorsProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinCompilerPluginsProvider
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinAlwaysAccessibleLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationTrackerFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderFactory
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackageProviderMerger
import org.jetbrains.kotlin.analysis.api.platform.permissions.KotlinAnalysisPermissionOptions
import org.jetbrains.kotlin.analysis.api.platform.resolution.KaResolutionActivityTracker
import org.jetbrains.kotlin.analysis.api.resolve.extensions.KaResolveExtensionProvider
import org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.modification.KotlinStandaloneModificationTrackerFactory
import org.jetbrains.kotlin.analysis.api.standalone.base.permissions.KotlinStandaloneAnalysisPermissionOptions
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.FirStandaloneServiceRegistrar
import org.jetbrains.kotlin.analysis.api.standalone.base.projectStructure.StandaloneProjectFactory
import org.jetbrains.kotlin.analysis.decompiler.konan.KlibMetaFileType
import org.jetbrains.kotlin.analysis.decompiler.konan.KotlinKlibMetadataDecompiler
import org.jetbrains.kotlin.analysis.decompiler.psi.KotlinBuiltInDecompiler
import org.jetbrains.kotlin.analysis.decompiler.psi.KotlinBuiltInFileType
import org.jetbrains.kotlin.analysis.decompiler.psi.KotlinClassFileDecompiler
import org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionConfigurator
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironmentMode
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreProjectEnvironment
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.scripting.definitions.ScriptConfigurationsProvider
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionProvider
import org.jetbrains.kotlin.serialization.deserialization.METADATA_FILE_EXTENSION
import org.jetbrains.kotlin.serialization.deserialization.builtins.BuiltInSerializerProtocol
import ru.hollowhorizon.hollowengine.common.ide.session.index.JavaClassNameIndex
import ru.hollowhorizon.hollowengine.common.ide.session.modules.KaJarLibraryModuleImpl
import ru.hollowhorizon.hollowengine.common.ide.session.modules.KaJdkLibraryModuleImpl
import ru.hollowhorizon.hollowengine.common.ide.session.modules.KaRekotLibraryModule
import java.nio.file.Path
import java.util.*
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectory
import kotlin.io.path.exists
import kotlin.script.experimental.api.SourceCode

private const val KLIB_METADATA_FILE_EXTENSION = "knm"

class AnalysisEnvironment(
    val classpath: List<Path>,
    val scriptDefinitions: List<ScriptDefinition> = emptyList(),
    val javaHome: Path? = null,
) {
    private val projectDisposable = Disposer.newDisposable("AnalysisEnvironment")
    private val compilerPluginSupport = AnalysisCompilerPluginSupport.create()
    private var scriptConfigurationsProvider: ClassLiteralScriptConfigurationsProvider? = null
    private val resolvedJavaHome = javaHome ?: Path.of(System.getProperty("java.home"))
    private val jdkClasses = LibraryUtils.findClassesFromJdkHome(resolvedJavaHome, isJre = true)
        .ifEmpty { LibraryUtils.findClassesFromJdkHome(resolvedJavaHome, isJre = false) }
        .distinct()
    val kotlinCoreProjectEnvironment: KotlinCoreProjectEnvironment
    val project: MockProject
    val analyzer: ScriptingAnalyzerImpl

    init {
        // Установка временной директории для Idea
        setupIdeaHome()

        Logger.setFactory { EmptyLogger }

        // Создание окружения проекта
        kotlinCoreProjectEnvironment = StandaloneProjectFactory.createProjectEnvironment(
            projectDisposable,
            KotlinCoreApplicationEnvironmentMode.Production,
        )

        project = kotlinCoreProjectEnvironment.project

        // Регистрация расширений и сервисов
        registerExtensionPoints()
        registerApplicationServices()
        registerDecompilers()

        // Создание библиотечных модулей
        val libraries = createLibraryModules()

        // Создание и настройка провайдера структуры проекта
        val projectStructureProvider = ProjectStructureProviderImpl()
        libraries.forEach { projectStructureProvider.registerLibraryModule(it) }

        val builtins = Builtins(kotlinCoreProjectEnvironment)
        // Регистрация сервисов проекта
        registerProjectServices(builtins, libraries, projectStructureProvider)

        // Создание структуры проекта
        analyzer = ScriptingAnalyzerImpl(
            kotlinCoreProjectEnvironment,
            libraries,
            builtins,
            projectStructureProvider
        )

        // Настройка провайдера скриптов
        setupScriptDefinitions()
    }

    private fun setupIdeaHome() {
        val key = "idea.home.path"
        if (System.getProperty(key) == null) {
            val tempDir = System.getProperty("java.io.tmpdir")
            val ideaHome = Path.of(tempDir, "ideaHomePath").apply {
                if (!exists()) createDirectory()
            }
            System.setProperty(key, ideaHome.absolutePathString())
        }
    }

    private fun registerExtensionPoints() {
        registerProjectExtensionPoint(
            KaResolveExtensionProvider.EP_NAME.name,
            KaResolveExtensionProvider::class.java.name,
        )
        LLFirSessionConfigurator.registerExtensionPoint(project)
        registerProjectExtensionPoint(
            "org.jetbrains.kotlin.fir.extensions.firExtensionRegistrar",
            "org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter",
        )
        registerProjectExtensionPoint(
            "org.jetbrains.kotlin.kotlinContentScopeRefiner",
            "org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinContentScopeRefiner",
        )
        registerProjectExtensionPoint(
            "org.jetbrains.kotlin.kotlinGlobalSearchScopeMergeStrategy",
            "org.jetbrains.kotlin.analysis.api.platform.projectStructure.KotlinGlobalSearchScopeMergeStrategy",
        )
        registerProjectExtensionPoint(
            "org.jetbrains.kotlin.psiReferenceProvider",
            "org.jetbrains.kotlin.references.KotlinPsiReferenceProviderContributor",
        )
        registerProjectExtensionPoint(
            "org.jetbrains.kotlin.kaAdditionalKDocResolutionProvider",
            "org.jetbrains.kotlin.analysis.api.symbols.KaAdditionalKDocResolutionProvider",
        )
        registerProjectExtensionPoint(
            "org.jetbrains.kotlin.analysis.additionalKDocResolutionProvider",
            "org.jetbrains.kotlin.analysis.api.symbols.AdditionalKDocResolutionProvider",
        )
        registerProjectExtension(
            "org.jetbrains.kotlin.kotlinContentScopeRefiner",
            "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaResolveExtensionToContentScopeRefinerBridge",
        )
        registerProjectExtension(
            "org.jetbrains.kotlin.kotlinContentScopeRefiner",
            "org.jetbrains.kotlin.analysis.api.fir.projectStructure.KaFirLibraryTargetPlatformContentScopeRefiner",
        )
        registerProjectExtension(
            "org.jetbrains.kotlin.kotlinGlobalSearchScopeMergeStrategy",
            "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KotlinResolveExtensionGeneratedFileScopeMergeStrategy",
        )
        registerProjectExtension(
            "org.jetbrains.kotlin.kaAdditionalKDocResolutionProvider",
            "org.jetbrains.kotlin.analysis.api.fir.references.KaAdditionalKDocResolutionProviderAdapter",
        )
        listOf(
            "org.jetbrains.kotlin.analysis.api.fir.references.KaFirForLoopInReference\$Provider",
            "org.jetbrains.kotlin.analysis.api.fir.references.KaFirInvokeFunctionReference\$Provider",
            "org.jetbrains.kotlin.analysis.api.fir.references.KaFirPropertyDelegationMethodsReference\$Provider",
            "org.jetbrains.kotlin.analysis.api.fir.references.KaFirDestructuringDeclarationReference\$Provider",
            "org.jetbrains.kotlin.analysis.api.fir.references.KaFirArrayAccessReference\$Provider",
            "org.jetbrains.kotlin.analysis.api.fir.references.KaFirConstructorDelegationReference\$Provider",
            "org.jetbrains.kotlin.analysis.api.fir.references.KaFirCollectionLiteralReference\$Provider",
            "org.jetbrains.kotlin.analysis.api.fir.references.KaFirKDocReference\$Provider",
            "org.jetbrains.kotlin.analysis.api.fir.references.KaFirSimpleNameReference\$Provider",
            "org.jetbrains.kotlin.analysis.api.fir.references.KaFirDefaultAnnotationArgumentReference\$Provider",
        ).forEach { provider ->
            registerProjectExtension("org.jetbrains.kotlin.psiReferenceProvider", provider)
        }
        LLFirSessionConfigurator.registerExtension(
            project,
            compilerPluginSupport.sessionConfigurator,
        )
    }

    private fun registerApplicationServices() {
        KotlinCoreEnvironment.underApplicationLock {
            val applicationEnvironment = kotlinCoreProjectEnvironment.environment
            val application = applicationEnvironment.application

            registerApplicationExtensionPoint(
                "com.intellij.syntax.elementTypeConverter",
                LanguageExtensionPoint::class.java.name,
            )
            registerApplicationLanguageExtension(
                "com.intellij.syntax.elementTypeConverter",
                "any",
                "com.intellij.platform.syntax.psi.CommonElementTypeConverterFactory",
            )
            registerApplicationLanguageExtension(
                "com.intellij.syntax.elementTypeConverter",
                "JAVA",
                "com.intellij.lang.java.syntax.JavaElementTypeConverterExtension",
            )
            registerApplicationLanguageExtension(
                "com.intellij.syntax.elementTypeConverter",
                "JSP",
                "com.intellij.lang.java.syntax.JShellElementTypeConverterExtension",
            )

            application.registerApplicationServiceIfMissing(
                KotlinAnalysisPermissionOptions::class.java,
                KotlinStandaloneAnalysisPermissionOptions(),
            )

            application.registerApplicationServiceIfMissing(
                KaAnalysisPermissionRegistry::class.java,
                object : KaAnalysisPermissionRegistry {
                    override var explicitAnalysisRestriction: KaAnalysisPermissionRegistry.KaExplicitAnalysisRestriction? =
                        null
                    override var isAnalysisAllowedOnEdt: Boolean = true
                    override var isAnalysisAllowedInWriteAction: Boolean = true
                },
            )

            application.registerApplicationServiceIfMissing(
                KaResolutionActivityTracker::class.java.name,
                "org.jetbrains.kotlin.analysis.low.level.api.fir.lazy.resolve.LLFirResolutionActivityTracker",
            )
            application.registerApplicationServiceIfMissing(
                "org.jetbrains.kotlin.analysis.decompiler.psi.KotlinBuiltInDecompilationInterceptor",
                "org.jetbrains.kotlin.analysis.decompiler.psi.K2KotlinBuiltInDecompilationInterceptor",
            )
            application.registerApplicationServiceIfMissing(
                "org.jetbrains.kotlin.analysis.decompiler.psi.KotlinBuiltInStubVersionOffsetProvider",
                "org.jetbrains.kotlin.analysis.decompiler.psi.K2KotlinBuiltInStubVersionOffsetProvider",
            )
            application.registerApplicationServiceIfMissing(
                "org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionModeProvider",
                "org.jetbrains.kotlin.analysis.api.fir.projectStructure.KaFirDanglingFileResolutionModeProvider",
            )
            application.registerApplicationServiceIfMissing(
                "com.intellij.platform.syntax.psi.PsiSyntaxBuilderFactory",
                "com.intellij.platform.syntax.psi.PsiSyntaxBuilderFactoryImpl",
            )
            application.registerApplicationServiceIfMissing(
                "org.jetbrains.kotlin.analysis.api.standalone.base.declarations.KotlinStandaloneIndexCache",
            )
        }
    }

    private fun registerDecompilers() {
        val applicationEnvironment = kotlinCoreProjectEnvironment.environment
        val applicationDisposable = kotlinCoreProjectEnvironment.environment.parentDisposable

        applicationEnvironment.registerFileType(
            KotlinBuiltInFileType,
            BuiltInSerializerProtocol.BUILTINS_FILE_EXTENSION
        )
        applicationEnvironment.registerFileType(KotlinBuiltInFileType, METADATA_FILE_EXTENSION)
        applicationEnvironment.registerFileType(KlibMetaFileType, KLIB_METADATA_FILE_EXTENSION)

        for (fileType in listOf(JavaClassFileType.INSTANCE, KotlinBuiltInFileType, KlibMetaFileType)) {
            FileTypeFileViewProviders.INSTANCE.addExplicitExtension(
                fileType,
                ClassFileViewProviderFactory(),
                applicationDisposable,
            )

            BinaryFileStubBuilders.INSTANCE.addExplicitExtension(
                fileType,
                ClassFileStubBuilder(),
                applicationDisposable
            )
            BinaryFileTypeDecompilers.getInstance()
                .addExplicitExtension(fileType, ClassFileDecompiler(), applicationDisposable)
        }

        ClassFileDecompilers.getInstance().EP_NAME.point.apply {
            registerExtension(KotlinClassFileDecompiler(), LoadingOrder.FIRST, applicationDisposable)
            registerExtension(KotlinBuiltInDecompiler(), LoadingOrder.FIRST, applicationDisposable)
            registerExtension(
                KotlinKlibMetadataDecompiler(),
                LoadingOrder.FIRST,
                applicationDisposable,
            )

            registerExtension(ClsDecompilerImpl(), LoadingOrder.FIRST, applicationDisposable)
        }
    }

    private fun createLibraryModules(): List<KaRekotLibraryModule> {
        val libraries = mutableListOf<KaRekotLibraryModule>()

        // Добавление JDK
        libraries.add(
            KaJdkLibraryModuleImpl(
                resolvedJavaHome,
                jdkClasses,
                "JDK",
                kotlinCoreProjectEnvironment.project,
            )
        )

        // Добавление пользовательских библиотек из classpath
        libraries.add(
            KaJarLibraryModuleImpl(
                classpath,
                "ClassPath",
                kotlinCoreProjectEnvironment.project,
            )
        )

        return libraries
    }

    private fun registerProjectServices(
        builtins: Builtins,
        libraries: List<KaRekotLibraryModule>,
        projectStructureProvider: ProjectStructureProviderImpl,
    ) {
        StandaloneProjectFactory.registerServicesForProjectEnvironment(
            kotlinCoreProjectEnvironment,
            projectStructureProvider,
            HollowEngineLanguageSettings.INSTANCE,
            resolvedJavaHome,
        )

        project.apply {
            registerFirAnalysisServices()
            registerService(KotlinMessageBusProvider::class.java, KotlinProjectMessageBusProvider::class.java)
            // FirStandaloneServiceRegistrar.registerProjectServices(project)
            FirStandaloneServiceRegistrar.registerProjectExtensionPoints(project)
            FirStandaloneServiceRegistrar.registerProjectModelServices(
                project,
                kotlinCoreProjectEnvironment.parentDisposable,
            )

            registerService(
                KotlinCompilerPluginsProvider::class.java,
                compilerPluginSupport.provider,
            )

            registerService(
                KotlinModificationTrackerFactory::class.java,
                KotlinStandaloneModificationTrackerFactory::class.java,
            )
            registerService(
                KotlinLifetimeTokenFactory::class.java,
                KotlinAlwaysAccessibleLifetimeTokenFactory::class.java,
            )

            registerService(
                KotlinAnnotationsResolverFactory::class.java,
                KotlinStandaloneAnnotationsResolverFactory(project, emptyList()),
            )

            registerService(
                KotlinDeclarationProviderFactory::class.java,
                SimpleDeclarationProviderFactory(
                    kotlinCoreProjectEnvironment,
                    builtins,
                    jdkClasses + classpath,
                ).also { declarationProviderFactory ->
                    registerService(JavaClassNameIndex::class.java, declarationProviderFactory.javaClassNameIndex)
                }
            )

            registerService(
                KotlinDeclarationProviderMerger::class.java,
                HollowDeclarationProviderMerger(this),
            )

            registerService(
                KotlinPackageProviderFactory::class.java,
                HollowPackageProviderFactory(project, projectStructureProvider),
            )

            registerService(
                KotlinPackageProviderMerger::class.java,
                HollowPackageProviderMerger(this),
            )

            registerService(
                KotlinPackagePartProviderFactory::class.java,
                object : KotlinPackagePartProviderFactory {
                    private val cache = WeakHashMap<GlobalSearchScope, PackagePartProvider>()

                    override fun createPackagePartProvider(scope: GlobalSearchScope): PackagePartProvider {
                        return cache.getOrPut(scope) {
                            StandaloneProjectFactory.createPackagePartsProvider(
                                StandaloneProjectFactory.getAllBinaryRoots(
                                    libraries,
                                    kotlinCoreProjectEnvironment.environment,
                                )
                            )(scope)
                        }
                    }
                },
            )

            // Инициализация сервисов для работы с виртуальными файлами
            registerServiceIfMissing(
                KotlinPlatformSettings::class.java,
                object : KotlinPlatformSettings {
                    override val deserializedDeclarationsOrigin: KotlinDeserializedDeclarationsOrigin
                        get() = KotlinDeserializedDeclarationsOrigin.STUBS
                },
            )
        }
    }

    private fun registerProjectExtensionPoint(name: String, interfaceClassName: String) {
        if (project.extensionArea.getExtensionPointIfRegistered<Any>(name) != null) return
        CoreApplicationEnvironment.registerExtensionPoint(
            project.extensionArea,
            name,
            loadClass<Any>(interfaceClassName),
        )
    }

    private fun registerProjectExtension(name: String, implementationClassName: String) {
        val point = project.extensionArea.getExtensionPointIfRegistered<Any>(name) ?: return
        val implementation = loadClass<Any>(implementationClassName)
            .getDeclaredConstructor()
            .newInstance()
        point.registerExtension(implementation, LoadingOrder.ANY, projectDisposable)
    }

    private fun registerApplicationExtensionPoint(name: String, beanClassName: String) {
        val applicationArea = kotlinCoreProjectEnvironment.environment.application.extensionArea
        if (applicationArea.getExtensionPointIfRegistered<Any>(name) != null) return
        CoreApplicationEnvironment.registerApplicationExtensionPoint(
            ExtensionPointName.create(name),
            loadClass<Any>(beanClassName),
        )
    }

    private fun registerApplicationLanguageExtension(
        extensionPointName: String,
        language: String,
        implementationClassName: String,
    ) {
        val applicationArea = kotlinCoreProjectEnvironment.environment.application.extensionArea
        val point = applicationArea
            .getExtensionPointIfRegistered<LanguageExtensionPoint<Any>>(extensionPointName)
            ?: return
        val alreadyRegistered = point.extensionList.any { extension ->
            extension.language == language && extension.implementationClass == implementationClassName
        }
        if (alreadyRegistered) return

        val extension = LanguageExtensionPoint<Any>(
            language,
            implementationClassName,
            DefaultPluginDescriptor("hollowengine-analysis-api"),
        )
        point.registerExtension(extension, LoadingOrder.ANY, projectDisposable)
    }

    private fun MockProject.registerFirAnalysisServices() {
        registerServiceIfMissing(
            "org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade",
            "org.jetbrains.kotlin.analysis.api.impl.base.java.KaBaseKotlinJavaPsiFacade",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleResolver",
            "org.jetbrains.kotlin.analysis.api.impl.base.java.KaBaseJavaModuleResolver",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.load.java.structure.impl.source.JavaElementSourceFactory",
            "org.jetbrains.kotlin.analysis.api.impl.base.java.source.JavaElementSourceWithSmartPointerFactory",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.psi.KotlinReferenceProvidersService",
            "org.jetbrains.kotlin.analysis.api.impl.base.references.KotlinReferenceProvidersServiceImpl",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider",
            "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseModuleProvider",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.analysis.api.platform.permissions.KaAnalysisPermissionChecker",
            "org.jetbrains.kotlin.analysis.api.impl.base.permissions.KaBaseAnalysisPermissionChecker",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScopeProvider",
            "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseResolutionScopeProvider",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.analysis.api.platform.lifetime.KaLifetimeTracker",
            "org.jetbrains.kotlin.analysis.api.impl.base.lifetime.KaBaseLifetimeTracker",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaContentScopeProvider",
            "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KaBaseContentScopeProvider",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaGlobalSearchScopeMerger",
            "org.jetbrains.kotlin.analysis.api.impl.base.projectStructure.KotlinOptimizingGlobalSearchScopeMerger",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.analysis.api.session.KaSessionProvider",
            "org.jetbrains.kotlin.analysis.api.fir.KaFirSessionProvider",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.analysis.api.platform.modification.KaSourceModificationService",
            "org.jetbrains.kotlin.analysis.api.fir.modification.KaFirSourceModificationService",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.idea.references.ReadWriteAccessChecker",
            "org.jetbrains.kotlin.analysis.api.fir.references.ReadWriteAccessCheckerFirImpl",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.analysis.api.imports.KaDefaultImportsProvider",
            "org.jetbrains.kotlin.analysis.api.fir.KaFirDefaultImportsProvider",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.analysis.api.platform.statistics.KaStatisticsService",
            "org.jetbrains.kotlin.analysis.api.fir.statistics.KaFirStatisticsService",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.references.utils.KotlinKDocResolutionStrategyProviderService",
            "org.jetbrains.kotlin.analysis.api.fir.references.KotlinFirKDocResolutionStrategyProviderService",
        )
        registerService(
            KotlinDirectInheritorsProvider::class.java,
            HollowDirectInheritorsProvider(this),
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.analysis.low.level.api.fir.api.services.LLFirElementByPsiElementChooser",
            "org.jetbrains.kotlin.analysis.api.standalone.base.services.LLStandaloneFirElementByPsiElementChooser",
        )
        registerServiceIfMissing(
            "org.jetbrains.kotlin.asJava.KotlinAsJavaSupport",
            "org.jetbrains.kotlin.light.classes.symbol.SymbolKotlinAsJavaSupport",
        )

        registerServiceIfMissing(
            "org.jetbrains.kotlin.analysis.api.fir.utils.KaFirCacheCleaner",
            "org.jetbrains.kotlin.analysis.api.fir.utils.KaFirStopWorldCacheCleaner",
        )
        registerServiceIfMissing("org.jetbrains.kotlin.analysis.low.level.api.fir.projectStructure.LLFirBuiltinsSessionFactory")
        registerServiceIfMissing("org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionCache")
        registerServiceIfMissing("org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationService")
        registerServiceIfMissing("org.jetbrains.kotlin.analysis.low.level.api.fir.sessions.LLFirSessionInvalidationEventPublisher")
        registerServiceIfMissing("org.jetbrains.kotlin.analysis.low.level.api.fir.LLFirGlobalResolveComponents")
        registerServiceIfMissing("org.jetbrains.kotlin.analysis.low.level.api.fir.LLResolutionFacadeService")
        registerServiceIfMissing("org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirDeclarationModificationService")
        registerServiceIfMissing("org.jetbrains.kotlin.analysis.low.level.api.fir.file.structure.LLFirInBlockModificationTracker")
        registerServiceIfMissing("org.jetbrains.kotlin.analysis.low.level.api.fir.statistics.LLStatisticsService")
    }

    private fun setupScriptDefinitions() {
        if (scriptDefinitions.isNotEmpty()) {
            val prioritizedDefinitions = scriptDefinitions.sortedByDescending { it.fileExtension.length }
            val defaultDefinition = scriptDefinitions
                .firstOrNull { it.fileExtension == ".kts" }
                ?: scriptDefinitions.minByOrNull { it.fileExtension.length }
                ?: prioritizedDefinitions.first()

            val definitionProvider = object : ScriptDefinitionProvider {
                override fun findDefinition(script: SourceCode): ScriptDefinition? {
                    return prioritizedDefinitions.firstOrNull { definition ->
                        script.matchesExtension(definition.fileExtension)
                    }
                }

                override fun getDefaultDefinition(): ScriptDefinition {
                    return defaultDefinition
                }

                override fun getKnownFilenameExtensions(): Sequence<String> {
                    return prioritizedDefinitions
                        .asSequence()
                        .map { it.fileExtension.removePrefix(".") }
                        .distinct()
                }

                override val currentDefinitions: Sequence<ScriptDefinition>
                    get() = prioritizedDefinitions.asSequence()

                override fun isScript(script: SourceCode): Boolean {
                    return prioritizedDefinitions.any { definition ->
                        script.matchesExtension(definition.fileExtension)
                    }
                }
            }
            project.registerService(ScriptDefinitionProvider::class.java, definitionProvider)
            ClassLiteralScriptConfigurationsProvider(
                project,
                definitionProvider,
                classpath,
            ).also { provider ->
                project.registerService(ScriptConfigurationsProvider::class.java, provider)
                scriptConfigurationsProvider = provider
            }
        }
    }

    fun dispose() {
        scriptConfigurationsProvider?.close()
        Disposer.dispose(projectDisposable)
    }

    fun warmUpClassIndex() {
        JavaClassNameIndex.getInstance(project).warmUp()
    }
}

private fun SourceCode.matchesExtension(extension: String): Boolean {
    return sequenceOf(locationId, name)
        .filterNotNull()
        .any { it.endsWith(extension, ignoreCase = true) }
}

private fun <T : Any> MockApplication.registerApplicationServiceIfMissing(
    serviceInterface: Class<T>,
    serviceImplementation: T,
) {
    if (picoContainer.getComponentAdapter(serviceInterface) != null) return
    registerService(serviceInterface, serviceImplementation)
}

private fun MockApplication.registerApplicationServiceIfMissing(
    serviceInterfaceName: String,
    serviceImplementationName: String,
) {
    val serviceInterface = loadClass<Any>(serviceInterfaceName)
    val serviceImplementation = loadClass<Any>(serviceImplementationName).asSubclass(serviceInterface)
    if (picoContainer.getComponentAdapter(serviceInterface) != null) return
    registerApplicationServiceClass(serviceInterface, serviceImplementation)
}

private fun MockApplication.registerApplicationServiceIfMissing(serviceImplementationName: String) {
    val serviceImplementation = loadClass<Any>(serviceImplementationName)
    if (picoContainer.getComponentAdapter(serviceImplementation) != null) return
    registerService(serviceImplementation)
}

private fun <T : Any> MockProject.registerServiceIfMissing(
    serviceInterface: Class<T>,
    serviceImplementation: T,
) {
    if (picoContainer.getComponentAdapter(serviceInterface) != null) return
    registerService(serviceInterface, serviceImplementation)
}

private fun MockProject.registerServiceIfMissing(
    serviceInterfaceName: String,
    serviceImplementationName: String,
) {
    val serviceInterface = loadClass<Any>(serviceInterfaceName)
    val serviceImplementation = loadClass<Any>(serviceImplementationName).asSubclass(serviceInterface)
    if (picoContainer.getComponentAdapter(serviceInterface) != null) return
    registerProjectServiceClass(serviceInterface, serviceImplementation)
}

private fun MockProject.registerServiceIfMissing(serviceImplementationName: String) {
    val serviceImplementation = loadClass<Any>(serviceImplementationName)
    if (picoContainer.getComponentAdapter(serviceImplementation) != null) return
    registerService(serviceImplementation)
}

private fun <T : Any> MockApplication.registerApplicationServiceClass(
    serviceInterface: Class<T>,
    serviceImplementation: Class<out T>,
) {
    registerService(serviceInterface, serviceImplementation)
}

private fun <T : Any> MockProject.registerProjectServiceClass(
    serviceInterface: Class<T>,
    serviceImplementation: Class<out T>,
) {
    registerService(serviceInterface, serviceImplementation)
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> loadClass(className: String): Class<T> {
    return Class.forName(className) as Class<T>
}
