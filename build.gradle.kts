
import dev.architectury.plugin.ArchitectPluginExtension
import me.modmuss50.mpp.ReleaseType
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    base
    idea
    id("architectury-plugin") apply false
    id("dev.architectury.loom") apply false
    id("com.gradleup.shadow") apply false
    id("me.modmuss50.mod-publish-plugin")
    kotlin("jvm") apply false
    kotlin("plugin.serialization") apply false
    kotlin("plugin.compose") apply false
}

tasks.register<Sync>("buildAndCollect") {
    group = "build"
    into(layout.projectDirectory.dir("merged"))
}

fun Project.configureHollowAddon() {
    plugins.apply("base")
    plugins.apply("java-library")
    plugins.apply("org.jetbrains.kotlin.jvm")
    plugins.apply("org.jetbrains.kotlin.plugin.serialization")
    plugins.apply("architectury-plugin")
    plugins.apply("dev.architectury.loom")

    val modVersion = rootProject.property("modVersion") as String
    val modGroup = rootProject.property("modGroup") as String
    val minecraftVersion = rootProject.property("minecraftVersion") as String
    val parchmentVersion = rootProject.property("parchmentVersion") as String
    val fabricLoaderVersion = rootProject.property("fabricLoaderVersion") as String
    val kotlinVersion = rootProject.property("kotlinVersion") as String
    val serializationVersion = rootProject.property("serializationVersion") as String
    val koinVersion = rootProject.property("koinVersion") as String

    group = "$modGroup.addons"
    version = modVersion

    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.parchmentmc.org")
        maven("https://maven.blamejared.com/")
        maven("https://jitpack.io")
        maven("https://maven.google.com/")
        flatDir { dirs(rootProject.file("libs")) }
    }

    extensions.configure<ArchitectPluginExtension>("architectury") {
        common("fabric", "neoforge")
    }
    val loom = extensions.getByType<LoomGradleExtensionAPI>()
    loom.silentMojangMappingsLicense()

    fun Configuration.configureBundledLibraries() {
        isCanBeResolved = true
        isCanBeConsumed = false
        isTransitive = true
    }
    val addonLibraries = configurations.create("addonLibraries") {
        configureBundledLibraries()
    }
    val addonRuntimeLibraries = configurations.create("addonRuntimeLibraries") {
        configureBundledLibraries()
    }
    val addonBootstrapLibraries = configurations.create("addonBootstrapLibraries") {
        configureBundledLibraries()
    }
    configurations.named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME) {
        extendsFrom(addonLibraries, addonBootstrapLibraries)
    }
    configurations.named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME) {
        extendsFrom(addonLibraries, addonRuntimeLibraries, addonBootstrapLibraries)
    }
    configurations.named(JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME) {
        extendsFrom(addonLibraries, addonBootstrapLibraries)
    }
    configurations.named(JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME) {
        extendsFrom(addonLibraries, addonRuntimeLibraries, addonBootstrapLibraries)
    }

    dependencies {
        add("minecraft", "com.mojang:minecraft:$minecraftVersion")
        add("mappings", loom.layered {
            officialMojangMappings()
            parchment("org.parchmentmc.data:parchment-$minecraftVersion:$parchmentVersion")
        })
        add("modCompileOnly", "net.fabricmc:fabric-loader:$fabricLoaderVersion")
        add("compileOnly", project(path = ":runtime", configuration = "namedElements"))
        add("testImplementation", project(path = ":runtime", configuration = "namedElements"))
        add("compileOnly", "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
        add("compileOnly", "org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
        add("compileOnly", "org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
        add("compileOnly", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        add("compileOnly", "io.insert-koin:koin-core:$koinVersion")
        add("testImplementation", kotlin("test"))
    }

    tasks.named<Jar>("jar") {
        archiveClassifier.set("classes-named")
        include("**/*.class")
    }
    tasks.named<RemapJarTask>("remapJar") {
        enabled = false
    }

    apply(from = rootProject.file("gradle/addon-packaging.gradle.kts"))

    tasks.matching { it.name.startsWith("transformProduction") }.configureEach {
        enabled = false
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }
    tasks.withType<Test>().configureEach {
        val sandbox = layout.buildDirectory.dir("test-workdir")
        workingDir = sandbox.get().asFile
        doFirst { workingDir.mkdirs() }
    }
}

val specializedAddonProjectPaths = setOf(":addons:compiler")

fun Project.isHollowAddonProject(): Boolean =
    projectDir.resolve("build.gradle.kts").isFile &&
        projectDir.toPath().normalize().startsWith(rootProject.file("addons").toPath().normalize())

fun Project.usesHollowAddonConvention(): Boolean =
    isHollowAddonProject() && path !in specializedAddonProjectPaths

subprojects {
    plugins.apply("idea")

    layout.buildDirectory.set(
        rootProject.layout.projectDirectory.dir("build/${project.path.removePrefix(":").replace(':', '/')}")
    )

    idea {
        module {
            inheritOutputDirs = false
            outputDir = layout.buildDirectory.dir("idea/classes/main").get().asFile
            testOutputDir = layout.buildDirectory.dir("idea/classes/test").get().asFile
        }
    }

    if (usesHollowAddonConvention()) {
        configureHollowAddon()
    }
}

val enabledPlatforms = (property("enabledPlatforms") as String).split(',').map(String::trim).filter(String::isNotEmpty)
val modName = property("modName") as String
val modVersion = property("modVersion") as String
val minecraftVersion = property("minecraftVersion") as String
val modrinthProjectId = providers.gradleProperty("publish.modrinth")
val curseforgeProjectId = providers.gradleProperty("publish.curseforge")
val publishChangelogFile = layout.projectDirectory.file("CHANGELOG.md").asFile
val userProperties = Properties()
val userPropertiesFile = layout.projectDirectory.file("user.properties").asFile

if (userPropertiesFile.isFile) {
    userPropertiesFile.inputStream().use(userProperties::load)
}

fun userPropertyProvider(propertyName: String): Provider<String> {
    return providers.provider { userProperties.getProperty(propertyName) }
}

fun tokenProvider(
    gradlePropertyName: String,
    userPropertyName: String,
    vararg environmentNames: String,
): Provider<String> {
    val explicitProperty = providers.gradleProperty(gradlePropertyName)
        .orElse(userPropertyProvider(userPropertyName))
    return environmentNames.fold(explicitProperty) { provider, environmentName ->
        provider.orElse(providers.environmentVariable(environmentName))
    }
}

fun releaseTypeProvider(): Provider<ReleaseType> {
    return providers.gradleProperty("publish.releaseType")
        .orElse(providers.environmentVariable("RELEASE_TYPE"))
        .map { type ->
            when (type.lowercase()) {
                "alpha" -> ReleaseType.ALPHA
                "beta" -> ReleaseType.BETA
                "release", "stable" -> ReleaseType.STABLE
                else -> error("Unsupported publish.releaseType value '$type'. Use alpha, beta, or stable.")
            }
        }
        .orElse(ReleaseType.STABLE)
}

apply(from = rootProject.file("gradle/universal-jar.gradle.kts"))

publishMods {
    changelog.set(providers.gradleProperty("publish.changelog").orElse(providers.provider {
        if (publishChangelogFile.exists()) {
            publishChangelogFile.readText().trim()
        } else {
            "$modName $modVersion"
        }
    }))
    version.set(modVersion)
    type.set(releaseTypeProvider())
    dryRun.set(providers.gradleProperty("publish.dryRun").map(String::toBooleanStrict).orElse(true))

    val curseforgeOptions = curseforgeOptions {
        accessToken.set(tokenProvider("publish.curseforge.token", "curseforgeToken", "CURSEFORGE_TOKEN", "CURSEFORGE_API_KEY"))
        projectId.set(curseforgeProjectId)
        minecraftVersions.add(minecraftVersion)
        javaVersions.add(JavaVersion.VERSION_21)
        clientRequired.set(true)
        serverRequired.set(true)
    }

    val modrinthOptions = modrinthOptions {
        accessToken.set(tokenProvider("publish.modrinth.token", "modrinthToken", "MODRINTH_TOKEN", "MODRINTH_API_KEY"))
        projectId.set(modrinthProjectId)
        minecraftVersions.add(minecraftVersion)
    }

    val universalJar = tasks.named("universalJar")
    val universalJarFile = layout.file(universalJar.map { it.outputs.files.singleFile })

    curseforge {
        from(curseforgeOptions)
        file.set(universalJarFile)
        displayName.set("$modName $modVersion $minecraftVersion")
        modLoaders.addAll(enabledPlatforms)
        embeds("fabric-api")
    }

    modrinth {
        from(modrinthOptions)
        file.set(universalJarFile)
        displayName.set("$modName $modVersion $minecraftVersion")
        modLoaders.addAll(enabledPlatforms)
        embeds("fabric-api")
    }
}

tasks.named<Sync>("buildAndCollect") {
    val compilerJar = project(":addons:compiler").tasks.named<Jar>("addonJar")
    dependsOn(compilerJar)
    from(compilerJar.flatMap { it.archiveFile })
}

val buildAddons = tasks.register<Sync>("buildAddons") {
    group = "build"
    description = "Builds every addon discovered under addons/ and collects universal jars."
    into(layout.buildDirectory.dir("addon-jars"))
}

gradle.projectsEvaluated {
    subprojects
        .filter(Project::usesHollowAddonConvention)
        .forEach { addonProject ->
            val addonJar = addonProject.tasks.named<Jar>("addonJar")
            buildAddons.configure {
                dependsOn(addonJar)
                from(addonJar.flatMap { it.archiveFile })
            }
        }

    val compilerJar = project(":addons:compiler").tasks.named<Jar>("addonJar")
    buildAddons.configure {
        dependsOn(compilerJar)
        from(compilerJar.flatMap { it.archiveFile })
    }
}

tasks.named("buildAndCollect") {
    dependsOn(buildAddons)
}
