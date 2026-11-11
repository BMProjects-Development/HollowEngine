import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.CommandLineArgumentProvider
import java.util.Properties

val hollowScriptsDirectory = "scripts"
val hollowCompiledScriptsPath = "META-INF/hollowengine/scripts"
val hollowAddonClassesJar = "META-INF/hollowengine/classes.jar"
val hollowAddonRemapTablePath = "META-INF/hollowengine/remap-fabric.tbl.gz"
val hollowScriptPrecompiler = "ru.hollowhorizon.hollowengine.common.compiler.tools.ScriptPrecompiler"
val hollowRemapTableTool = "ru.hollowhorizon.hollowengine.runtime.remap.PayloadRemapTool"
val hollowScriptCompilerConfiguration = "hollowengineScriptCompiler"

/**
 * Runtime a precompiled script artifact is stamped for. Addons ship Mojang-named bytecode, which is what
 * NeoForge runs; on Fabric the game restamps it while applying the remap table.
 */
val namedScriptIdentity = "neoforge/official/production"

/** Whether a project ships the sources of its scripts next to the compiled artifacts. */
fun Project.shipsScriptSources(): Boolean =
    (findProperty("hollowengine.scripts.includeSources") as String?)?.toBooleanStrictOrNull() ?: true

apply(from = rootProject.file("gradle/addon-mod-metadata.gradle.kts"))
val addonModMetadata = tasks.named("generateAddonModMetadata")

fun Project.readHollowAddonId(): String {
    val descriptor = projectDir.resolve("src/main/resources/META-INF/plugin.properties")
    if (!descriptor.isFile) return name

    val properties = Properties()
    descriptor.inputStream().use(properties::load)
    return properties.getProperty("id")?.trim()?.takeIf(String::isNotEmpty) ?: name
}

/**
 * Everything the ahead-of-time script compiler needs to run: the compiler addon with its own
 * dependencies, plus this project's classes and runtime classpath, which is what the scripts compile
 * against.
 */
fun Project.hollowScriptCompilerClasspath(): FileCollection {
    val compilerProject = rootProject.project(":addons:compiler")
    val kotlinVersion = rootProject.property("kotlinVersion") as String
    val serializationVersion = rootProject.property("serializationVersion") as String
    val composeRuntimeVersion = rootProject.property("composeRuntimeVersion") as String
    val configuration = configurations.findByName(hollowScriptCompilerConfiguration)
        ?: configurations.create(hollowScriptCompilerConfiguration) {
            isCanBeResolved = true
            isCanBeConsumed = false
            isTransitive = true
        }.also { created ->
            // The packaged compiler, exactly the artifact the game loads. Taking the jar rather than the
            // project dependency keeps the IntelliJ repositories out of every addon build.
            dependencies.add(
                created.name,
                files(compilerProject.tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }),
            )
            // Deliberately not packaged into that jar, because the game already provides them.
            listOf(
                "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion",
                "org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion",
                "org.jetbrains.kotlin:kotlin-script-runtime:$kotlinVersion",
                // Remapping rewrites Kotlin metadata alongside the bytecode.
                "org.jetbrains.kotlin:kotlin-metadata-jvm:$kotlinVersion",
                "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0",
                "org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion",
                // UI scripts are Compose, and the Compose plugin refuses to run without its runtime.
                "androidx.compose.runtime:runtime:$composeRuntimeVersion",
                "org.apache.logging.log4j:log4j-api:2.23.1",
                "org.apache.logging.log4j:log4j-core:2.23.1",
                // The remapper is the engine's own, which is written for the ASM of the game.
                "org.ow2.asm:asm-commons:9.9.1",
            ).forEach { notation -> dependencies.add(created.name, notation) }
            (dependencies.add(created.name, dependencies.project(mapOf("path" to ":bridge", "configuration" to "namedElements"))) as ModuleDependency)
                .isTransitive = false
        }
    val ownSources = extensions.getByType<SourceSetContainer>().named("main")
    return files(
        ownSources.map { it.output },
        // What the scripts themselves compile against: Minecraft, the engine runtime, Kotlin.
        ownSources.map { it.compileClasspath },
        configuration,
    )
}

/**
 * Compiles `src/main/resources/scripts` with the same compiler the game uses, into Mojang-named
 * artifacts that players who never install the compiler addon can run.
 */
fun Project.registerHollowScriptCompilation(
    scriptsDirectory: File,
    namespace: String,
    fingerprint: String,
): TaskProvider<JavaExec> {
    val outputDirectory = layout.buildDirectory.dir("hollowengine/scripts/named")
    val scriptFiles = fileTree(scriptsDirectory) { include("**/*.kts") }
    return tasks.register<JavaExec>("compileNamedScripts") {
        group = "build"
        description = "Compiles this project's scripts against Mojang names."
        mainClass.set(hollowScriptPrecompiler)
        classpath = hollowScriptCompilerClasspath()
        // The engine resolves its own directory relative to the working directory, and a build has no
        // business creating one next to the sources.
        workingDir = layout.buildDirectory.dir("hollowengine/precompiler").get().asFile
        inputs.files(scriptFiles).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("namespace", namespace)
        inputs.property("fingerprint", fingerprint)
        inputs.property("identity", namedScriptIdentity)
        outputs.dir(outputDirectory)
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "--scripts", scriptsDirectory.absolutePath,
                "--output", outputDirectory.get().asFile.absolutePath,
                "--namespace", namespace,
                "--fingerprint", fingerprint,
                "--identity", namedScriptIdentity,
                "--remap", "false",
                "--mappings", "",
            )
        })
        doFirst {
            workingDir.mkdirs()
        }
    }
}

/**
 * Records which Mojang names in [content] have to change on Fabric, and to what. The game applies the
 * table when it loads the addon there, so one jar serves both loaders.
 */
fun Project.registerHollowRemapTable(content: TaskProvider<Jar>, fabricClasspath: FileCollection): TaskProvider<JavaExec> {
    val gameVersion = rootProject.property("minecraftVersion") as String
    val relocation = rootProject.property("fabricRelocation") as String
    val mappings = rootProject.file("addons/compiler/src/main/resources/mappings-$gameVersion.tiny")
    val workDirectory = layout.buildDirectory.dir("hollowengine/remap")
    val table = workDirectory.map { it.file("remap-fabric.tbl.gz") }
    val ownSources = extensions.getByType<SourceSetContainer>().named("main")
    val engineClasses = configurations.create("hollowengineEngineClasses") {
        isCanBeResolved = true
        isCanBeConsumed = false
        isTransitive = false
    }
    dependencies.add(
        engineClasses.name,
        dependencies.project(mapOf("path" to ":runtime", "configuration" to "namedElements")),
    )
    return tasks.register<JavaExec>("generateAddonRemapTable") {
        group = "build"
        description = "Generates the table that remaps this addon for Fabric when it is loaded."
        mainClass.set(hollowRemapTableTool)
        classpath = hollowScriptCompilerClasspath()
        maxHeapSize = "2g"
        inputs.file(content.flatMap { it.archiveFile }).withPathSensitivity(PathSensitivity.NONE)
        inputs.file(mappings).withPathSensitivity(PathSensitivity.NONE)
        inputs.files(engineClasses).withPathSensitivity(PathSensitivity.NONE)
        inputs.files(fabricClasspath).withPathSensitivity(PathSensitivity.NONE)
        inputs.property("relocation", relocation)
        outputs.file(table)
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "--payload", content.get().archiveFile.get().asFile.absolutePath,
                "--mappings", mappings.absolutePath,
                "--classpath", (fabricClasspath + ownSources.get().compileClasspath).asPath,
                "--from", "named",
                "--to", "intermediary",
                "--output", table.get().asFile.absolutePath,
                "--work", workDirectory.get().asFile.resolve("work").absolutePath,
                "--relocate", relocation,
                "--keep", engineClasses.asPath,
            )
        })
    }
}

fun isHostProvidedAddonLibrary(fileName: String): Boolean = listOf(
    "kotlin-stdlib",
    "kotlin-reflect",
    "kotlinx-coroutines",
    "kotlinx-serialization-core-jvm-",
    "kotlinx-serialization-json-jvm-",
    "koin-core",
    "slf4j-",
    "log4j-",
    "annotations-",
    "lwjgl",
    "jemalloc",
    "glfw",
    "openal",
    "opengl",
    "stb",
    "tinyfd",
    "shaderc",
    "vulkan",
    "jinput",
    "jna-",
    "jna-platform-",
    "netty-",
    "oshi-core",
).any(fileName::startsWith)

fun isHostNativeAddonLibrary(fileName: String): Boolean = listOf(
    "lwjgl",
    "jemalloc",
    "glfw",
    "openal",
    "opengl",
    "stb",
    "tinyfd",
    "shaderc",
    "vulkan",
    "jinput",
    "jna-",
    "jna-platform-",
    "netty-",
    "oshi-core",
).any(fileName.lowercase()::startsWith)

val addonLibraries = configurations.getByName("addonLibraries")
val addonRuntimeLibraries = configurations.getByName("addonRuntimeLibraries")
val addonBootstrapLibraries = configurations.getByName("addonBootstrapLibraries")

val processAddonResources = tasks.named<ProcessResources>("processResources") {
    filesMatching("META-INF/plugin.properties") {
        expand("version" to version)
    }
}
val namedClassesJar = tasks.named<Jar>("jar")
val addonNamespace = readHollowAddonId()

// Loaders the addon runs on and those whose API it uses, from `platform` of its descriptor; see
// addon-mod-metadata.gradle.kts.
@Suppress("UNCHECKED_CAST")
val addonPlatforms = extra["hollowAddonPlatforms"] as List<String>
@Suppress("UNCHECKED_CAST")
val addonLoaderApis = extra["hollowAddonLoaderApis"] as List<String>

// The parts written against one loader's API. They are compiled apart from src/main and travel in
// jars of their own, so the game only ever opens the one made for the loader it runs on.
val platformSourceSets = listOf("fabric", "neoforge").mapNotNull(extensions.getByType<SourceSetContainer>()::findByName)
platformSourceSets.forEach { sourceSet ->
    require(addonLoaderApis.isNotEmpty()) {
        "Addon '$addonNamespace' is common: it knows no mod loader and can have no src/${sourceSet.name}. " +
            "Name the loaders it is written against, as in platform=fabric,neoforge"
    }
    require(sourceSet.name in addonLoaderApis) {
        "Addon '$addonNamespace' is declared for ${addonLoaderApis.joinToString()} and can have no src/${sourceSet.name}"
    }
    val resources = projectDir.resolve("src/${sourceSet.name}/resources")
    require(!resources.walkTopDown().any(File::isFile)) {
        "Addon '$addonNamespace': $resources would not reach the jar, keep resources in src/main/resources"
    }
}
val platformClassesJars = platformSourceSets.associate { sourceSet ->
    sourceSet.name to tasks.register<Jar>("${sourceSet.name}ClassesJar") {
        group = "build"
        description = "Packs the classes this addon has for ${sourceSet.name} only."
        archiveClassifier.set("classes-${sourceSet.name}-named")
        from(sourceSet.output.classesDirs)
        include("**/*.class", "META-INF/*.kotlin_module")
    }
}

val scriptsDirectory = projectDir.resolve("src/main/resources/$hollowScriptsDirectory")
val compileNamedScripts = registerHollowScriptCompilation(
    scriptsDirectory = scriptsDirectory,
    namespace = addonNamespace,
    fingerprint = version.toString(),
)

// The classes and compiled scripts laid out as the game sees them on Fabric once it has unpacked the
// addon, which is what the remap table is recorded from.
val addonContentJar = tasks.register<Jar>("addonContentJar") {
    archiveClassifier.set("content-named")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    platformClassesJars["fabric"]?.let { fabricClasses -> from(zipTree(fabricClasses.flatMap { it.archiveFile })) }
    from(zipTree(namedClassesJar.flatMap { it.archiveFile }))
    from(compileNamedScripts) { into(hollowCompiledScriptsPath) }
}
// Only Fabric runs on other names than the ones an addon is compiled with.
val generateAddonRemapTable = if ("fabric" in addonPlatforms) {
    registerHollowRemapTable(
        addonContentJar,
        platformSourceSets.firstOrNull { it.name == "fabric" }?.compileClasspath ?: files(),
    )
} else null

val addonJar = tasks.register<Jar>("addonJar") {
    dependsOn(processAddonResources, addonModMetadata)
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    includeEmptyDirs = false
    manifest.attributes("HollowEngine-Addon-Format" to "3")
    from(processAddonResources) {
        exclude("$hollowScriptsDirectory/**")
    }
    from(scriptsDirectory) {
        into(hollowScriptsDirectory)
        if (!project.shipsScriptSources()) exclude("**/*.kts")
    }
    from(addonModMetadata)
    from(namedClassesJar) {
        into(hollowAddonClassesJar.substringBeforeLast('/'))
        rename { hollowAddonClassesJar.substringAfterLast('/') }
    }
    platformClassesJars.forEach { (platform, classesJar) ->
        from(classesJar) {
            into(hollowAddonClassesJar.substringBeforeLast('/'))
            rename { "classes-$platform.jar" }
        }
    }
    from(compileNamedScripts) { into(hollowCompiledScriptsPath) }
    generateAddonRemapTable?.let { remapTable ->
        from(remapTable) {
            into(hollowAddonRemapTablePath.substringBeforeLast('/'))
        }
    }
    from(addonLibraries) {
        into("hollowengine-addon-libs")
        exclude { details -> isHostProvidedAddonLibrary(details.file.name) }
    }
    from(addonRuntimeLibraries) {
        into("hollowengine-addon-libs")
        exclude { details -> isHostProvidedAddonLibrary(details.file.name) }
    }
    from(addonBootstrapLibraries) {
        into("hollowengine-addon-bootstrap")
        exclude { details -> isHostProvidedAddonLibrary(details.file.name) }
    }
    doFirst {
        val forbiddenLibraries = (addonLibraries + addonRuntimeLibraries + addonBootstrapLibraries)
            .files
            .filter { file -> isHostNativeAddonLibrary(file.name) }
        check(forbiddenLibraries.isEmpty()) {
            "Addons must use Minecraft's native libraries instead of bundling: " +
                forbiddenLibraries.joinToString { file -> file.name }
        }

        // The game lays a loader's classes over the common ones, where a second class of the same
        // name would silently replace the first.
        fun classesOf(jar: TaskProvider<Jar>): Set<String> {
            val names = HashSet<String>()
            zipTree(jar.get().archiveFile).matching { include("**/*.class") }.visit {
                if (!isDirectory) names += relativePath.pathString
            }
            return names
        }
        val commonClasses = classesOf(namedClassesJar)
        platformClassesJars.forEach { (platform, classesJar) ->
            val repeated = classesOf(classesJar).intersect(commonClasses)
            check(repeated.isEmpty()) {
                "Addon '$addonNamespace' defines the same classes in src/main and src/$platform: " +
                    repeated.sorted().take(5).joinToString()
            }
        }
    }
}

tasks.named("assemble") {
    dependsOn(addonJar)
}
