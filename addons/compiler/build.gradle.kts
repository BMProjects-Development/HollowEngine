
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.zip.ZipFile

plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gradleup.shadow")
}

val modVersion: String by rootProject.properties

base {
    archivesName = "HollowEngineCompiler"
}

version = modVersion

repositories {
    mavenCentral()
}

val kotlinVersion: String by rootProject.properties
val composeRuntimeVersion: String by rootProject.properties
val serializationVersion: String by rootProject.properties

val ijPlatform = "261.25134.147"
val ijJava = "261.25134.137"

val runtimeProjectPath = ":runtime"
val lightTreeClasses = project.projectDir.resolve("lightTree")
val patchedLightTreeJar = tasks.register<ShadowJar>("patchedLightTreeJar") {
    archiveBaseName.set("hollowengine-light-tree")
    archiveClassifier.set("patched")
    archiveVersion.set(kotlinVersion)
    from(lightTreeClasses)
    relocate("org.jetbrains.kotlin.com.intellij", "com.intellij")
    relocate("org.jetbrains.kotlin.com.google", "com.google")
}

val compilerPlugins = configurations.create("compilerPlugins") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
configurations.named(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME) {
    extendsFrom(compilerPlugins)
}
val compilerPluginRuntimeJar = tasks.register<ShadowJar>("compilerPluginRuntimeJar") {
    archiveBaseName.set("hollowengine-compiler-plugins")
    archiveClassifier.set("runtime")
    archiveVersion.set(kotlinVersion)
    configurations = listOf(compilerPlugins)
    exclude("META-INF/services/**")
}

repositories {
    mavenCentral()
    maven("https://www.jetbrains.com/intellij-repository/releases/")
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies/")
    maven("https://repo.spongepowered.org/repository/maven-public/")

}

dependencies {
    compileOnlyApi("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    compileOnly("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")

    api(project(path = runtimeProjectPath, configuration = "namedElements"))
    compileOnly("org.ow2.asm:asm-tree:9.7.1")
    testImplementation(project(":bridge")) { isTransitive = false }
    testImplementation("org.ow2.asm:asm-tree:9.7.1")

    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.4") { isTransitive = false }

    listOf(
        "com.jetbrains.intellij.platform:util-rt",
        "com.jetbrains.intellij.platform:util-class-loader",
        "com.jetbrains.intellij.platform:util",
        "com.jetbrains.intellij.platform:util-base",
        "com.jetbrains.intellij.platform:util-xml-dom",
        "com.jetbrains.intellij.platform:util-base-multiplatform",
        "com.jetbrains.intellij.platform:util-multiplatform",
        "com.jetbrains.intellij.platform:plugin-system-parser-impl",
        "com.jetbrains.intellij.platform:plugins-parser-impl:253.33813.35",
        "com.jetbrains.intellij.platform:util-jdom",
        "com.jetbrains.intellij.platform:core",
        "com.jetbrains.intellij.platform:core-impl",
        "com.jetbrains.intellij.platform:extensions",
        "com.jetbrains.intellij.platform:syntax-psi",
        "com.jetbrains.intellij.platform:syntax-i18-n",
        "com.jetbrains.intellij.platform:syntax",
        "com.jetbrains.intellij.platform:syntax-util",
        "com.intellij.platform:kotlinx-coroutines-core-jvm:1.10.1-intellij-5",
        "com.jetbrains.intellij.java:java-frontback-psi",
        "com.jetbrains.intellij.java:java-frontback-psi-impl",
        "com.jetbrains.intellij.java:java-indexing",
        "com.jetbrains.intellij.java:java-indexing-impl:261.25134.95",
        "com.jetbrains.intellij.java:java-psi",
        "com.jetbrains.intellij.java:java-psi-impl",
        "com.jetbrains.intellij.java:java-syntax",
        "com.jetbrains.intellij.java:java-impl:261.25134.95",
        "com.jetbrains.intellij.platform:diagnostic",
        "com.jetbrains.intellij.platform:diagnostic-telemetry",
        "com.jetbrains.intellij.platform:util-progress",
        "com.jetbrains.intellij.platform:util-coroutines",
        "com.jetbrains.intellij.platform:analysis"
    )
        .forEach {
            val version =
                when {
                    it.count(':'::equals) > 1 -> {
                        implementation(it) { isTransitive = false }
                        return@forEach
                    }

                    it.startsWith("com.jetbrains.intellij.platform") -> ijPlatform
                    else -> ijJava
                }

            implementation("$it:$version") { isTransitive = false }
        }

    implementation("io.opentelemetry:opentelemetry-api:1.44.1") { isTransitive = false }

    listOf(
        "org.jetbrains.kotlin:analysis-api-k2-for-ide",
        "org.jetbrains.kotlin:analysis-api-for-ide",
        "org.jetbrains.kotlin:low-level-api-fir-for-ide",
        "org.jetbrains.kotlin:analysis-api-platform-interface-for-ide",
        "org.jetbrains.kotlin:symbol-light-classes-for-ide",
        "org.jetbrains.kotlin:analysis-api-standalone-for-ide",
        "org.jetbrains.kotlin:analysis-api-impl-base-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-common-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fir-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fe10-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-ir-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-cli-for-ide",
        "org.jetbrains.kotlin:kotlin-scripting-common",
        "org.jetbrains.kotlin:kotlin-scripting-jvm",
        "org.jetbrains.kotlin:kotlin-scripting-jvm-host",
        "org.jetbrains.kotlin:kotlin-scripting-compiler-impl",
        "org.jetbrains.kotlin:kotlin-script-runtime",
        "org.jetbrains.kotlin:kotlin-scripting-compiler",
        "org.jetbrains.kotlin:assignment-compiler-plugin-for-ide",
    )
        .forEach {
            implementation("$it:$kotlinVersion") { isTransitive = false }
            implementation("$it:$kotlinVersion:sources") { isTransitive = false }
        }

    implementation("one.util:streamex:0.7.2") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps:asm-all:9.10.1") { isTransitive = false }
    implementation("org.codehaus.woodstox:stax2-api:4.2.1") { isTransitive = false }
    implementation("com.fasterxml:aalto-xml:1.3.0") { isTransitive = false }
    implementation("com.github.ben-manes.caffeine:caffeine:2.9.3") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps:trove4j:1.0.20200330") { isTransitive = false }
    implementation("io.vavr:vavr:0.10.7") { isTransitive = false }
    implementation("com.google.guava:guava:33.6.0-jre") { isTransitive = false }

    implementation("org.jetbrains.intellij.deps.fastutil:intellij-deps-fastutil:8.5.18-jb1") { isTransitive = false }
    implementation("org.benf:cfr:0.152") { isTransitive = false }

    add(compilerPlugins.name, "org.jetbrains.kotlin:kotlin-compose-compiler-plugin:$kotlinVersion")
    add(compilerPlugins.name, "org.jetbrains.kotlin:kotlin-serialization-compiler-plugin:$kotlinVersion")

    runtimeOnly(files(patchedLightTreeJar.flatMap { it.archiveFile }))

    testImplementation(kotlin("test"))
    testImplementation("androidx.compose.runtime:runtime:$composeRuntimeVersion")
    testRuntimeOnly(files(patchedLightTreeJar.flatMap { it.archiveFile }))
    testRuntimeOnly(files(compilerPluginRuntimeJar.flatMap { it.archiveFile }))
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    testRuntimeOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testRuntimeOnly("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
    testRuntimeOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    testRuntimeOnly("org.apache.logging.log4j:log4j-api:2.23.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}

val processAddonResources = tasks.named<ProcessResources>("processResources") {
    filesMatching("META-INF/plugin.properties") {
        expand("version" to version)
    }
}

val compilerClassesJar = tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("classes-agnostic")
    mergeServiceFiles()

    dependencies {
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib:.*"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib-jdk7:.*"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-stdlib-jdk8:.*"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-reflect:.*"))
        exclude(dependency("org.jetbrains.kotlin:kotlin-script-runtime:.*"))
        exclude(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:.*"))
        exclude(dependency("org.ow2.asm:.*"))
        exclude(project(runtimeProjectPath))
    }

    dependsOn(patchedLightTreeJar, compilerPluginRuntimeJar)
    from({ zipTree(patchedLightTreeJar.flatMap { it.archiveFile }) })
    from({ zipTree(compilerPluginRuntimeJar.flatMap { it.archiveFile }) })

    relocate("com.github.benmanes.caffeine", "ru.hollowhorizon.hollowengine.repackaged.caffeine")
    relocate("one.util.streamex", "ru.hollowhorizon.hollowengine.repackaged.streamex")
    relocate("io.vavr", "ru.hollowhorizon.hollowengine.repackaged.vavr")
    relocate("gnu.trove", "ru.hollowhorizon.hollowengine.repackaged.gnu.trove")
    relocate("org.benf.cfr", "ru.hollowhorizon.hollowengine.repackaged.cfr")
}

val verifyCompilerPluginPackaging = tasks.register("verifyCompilerPluginPackaging") {
    group = JavaBasePlugin.VERIFICATION_GROUP
    description = "Verifies compiler-plugin classes and manual registration in the packaged addon."
    dependsOn(compilerClassesJar)
    inputs.file(compilerClassesJar.flatMap { it.archiveFile })

    doLast {
        val registrarClasses = mapOf(
            "androidx.compose.compiler.plugins.kotlin.ComposePluginRegistrar" to
                    "androidx/compose/compiler/plugins/kotlin/ComposePluginRegistrar.class",
            "org.jetbrains.kotlinx.serialization.compiler.extensions.SerializationComponentRegistrar" to
                    "org/jetbrains/kotlinx/serialization/compiler/extensions/SerializationComponentRegistrar.class",
        )

        ZipFile(compilerClassesJar.get().archiveFile.get().asFile).use { archive ->
            val classCounts = registrarClasses.values.associateWith { 0 }.toMutableMap()
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entryName = entries.nextElement().name
                if (entryName in classCounts) {
                    classCounts[entryName] = classCounts.getValue(entryName) + 1
                }
            }
            check(classCounts.values.all { it == 1 }) {
                "Compiler plugin registrar classes must occur exactly once: $classCounts"
            }

            val registrarService = archive.getEntry(
                "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
            )
            val serviceProviders = registrarService?.let { entry ->
                archive.getInputStream(entry).bufferedReader().useLines { lines ->
                    lines.map(String::trim).filter(String::isNotEmpty).toSet()
                }
            }.orEmpty()
            val autoRegisteredPlugins = registrarClasses.keys.intersect(serviceProviders)
            check(autoRegisteredPlugins.isEmpty()) {
                "Compiler plugins must be registered manually, but services contain $autoRegisteredPlugins"
            }
        }
    }
}

apply(from = rootProject.file("gradle/addon-mod-metadata.gradle.kts"))
val addonModMetadata = tasks.named("generateAddonModMetadata")

val addonJar = tasks.register<Jar>("addonJar") {
    dependsOn(processAddonResources, compilerClassesJar, addonModMetadata)
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    includeEmptyDirs = false
    manifest.attributes("HollowEngine-Addon-Format" to "3")
    from(processAddonResources)
    from(addonModMetadata)
    from(compilerClassesJar) {
        into("META-INF/hollowengine")
        rename { "classes.jar" }
    }
}

tasks.build {
    dependsOn(addonJar, verifyCompilerPluginPackaging)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xopt-in=kotlin.contracts.ExperimentalContracts",
            "-Xopt-in=org.jetbrains.kotlin.analysis.api.KaExperimentalApi",
            "-Xopt-in=org.jetbrains.kotlin.analysis.api.KaImplementationDetail",
            "-Xopt-in=org.jetbrains.kotlin.analysis.api.KaPlatformInterface",
            "-Xopt-in=org.jetbrains.kotlin.analysis.api.KaIdeApi",
            "-Xopt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
        )
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "2g"
}
