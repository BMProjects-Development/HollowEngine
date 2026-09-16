
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.jar.JarFile

plugins {
    idea
    java
    `maven-publish`
    id("architectury-plugin")
    id("dev.architectury.loom")
    id("com.gradleup.shadow")
    kotlin("jvm")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
}

val modId: String by properties
val modName: String by properties
val modVersion: String by properties
val modGroup: String by properties
val minecraftVersion: String by rootProject.properties
val enabledPlatforms = (rootProject.property("enabledPlatforms") as String).split(',').map(String::trim).toTypedArray()
val fabricLoaderVersion: String by rootProject.properties
val architecturyApiVersion: String by rootProject.properties
val parchmentVersion: String by rootProject.properties
val kotlinVersion: String by rootProject.properties
val composeRuntimeVersion: String by rootProject.properties
val serializationVersion: String by rootProject.properties
val koolVersion: String by rootProject.properties
val koinVersion: String by rootProject.properties
val hollowcore: String by rootProject.properties

group = modGroup
version = modVersion
base {
    archivesName.set("${modName}Runtime")
}

apply(from = rootProject.file("gradle/assets-generator.gradle"))
apply(from = rootProject.file("gradle/lang-merge.gradle"))

val sourceSets = extensions.getByType<SourceSetContainer>()
val generatedAssetsDir = layout.buildDirectory.dir("generated/sources/assets/kotlin")
val mergedLangDir = layout.buildDirectory.dir("generated/lang/assets/$modId/lang")
val runtimeResourcesPath = sourceSets.named("main").get().output.resourcesDir
    ?.toPath()
    ?.toAbsolutePath()
    ?.normalize()
val runtimeMappingAttribute = Attribute.of("hollowengine.runtime.mapping", String::class.java)
val shadowBundle = configurations.create("shadowBundle") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = true
    exclude(group = "org.jetbrains", module = "annotations")
    exclude(group = "org.checkerframework", module = "checker-qual")
    exclude(group = "com.google.code.findbugs", module = "jsr305")
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
}

configurations.named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME) {
    extendsFrom(shadowBundle)
}

configurations.named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME) {
    extendsFrom(shadowBundle)
}

configurations.named(JavaPlugin.TEST_IMPLEMENTATION_CONFIGURATION_NAME) {
    extendsFrom(shadowBundle)
}

fun DependencyHandler.addShadow(
    notation: String,
    configure: ExternalModuleDependency.() -> Unit = {},
) {
    add("shadowBundle", notation, configure)
}

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

architectury {
    common(*enabledPlatforms)
}

loom {
    silentMojangMappingsLicense()

    val accessWidener = rootProject.file("runtime/src/main/resources/$modId.accesswidener")
    if (accessWidener.exists()) {
        accessWidenerPath.set(accessWidener)
    }
}

dependencies {
    "minecraft"("com.mojang:minecraft:$minecraftVersion")
    "mappings"(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-$minecraftVersion:$parchmentVersion")
    })

    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("lib:iris-fabric:1.8.8+mc1.21.1")
    modImplementation("lib:sodium-fabric:0.6.13+mc1.21.1")

    implementation(project(":bridge"))

    addShadow("net.peanuuutz.tomlkt:tomlkt:0.5.0")
    addShadow("com.github.weisj:jsvg:2.1.0")
    addShadow("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
    addShadow("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    addShadow("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
    addShadow("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    addShadow("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    addShadow("io.insert-koin:koin-core:$koinVersion")
    addShadow("org.jetbrains.kotlin:kotlin-metadata-jvm:$kotlinVersion")
    addShadow("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinVersion")
    addShadow("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")
    addShadow("org.jetbrains.kotlin:kotlin-script-runtime:$kotlinVersion")
    addShadow("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-compiler-embeddable")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-compiler-embeddable")
    }
    addShadow("io.github.classgraph:classgraph:4.8.173")
    addShadow("lib:kermit-core-mcfriendly:2.0.4")

    addShadow("androidx.compose.runtime:runtime:$composeRuntimeVersion")
    addShadow("androidx.collection:collection:1.4.0")
    addShadow("org.jetbrains.kotlinx:atomicfu:0.33.0")
    addShadow("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0")
    addShadow("org.jetbrains.kotlinx:kotlinx-io-bytestring:0.9.0")

    val jeiVersion = "19.25.1.332"
    add("modCompileOnly", "mezz.jei:jei-$minecraftVersion-fabric-api:$jeiVersion")

    compileOnly("org.jetbrains:annotations:26.1.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets.named("main").configure {
    java.setSrcDirs(
        listOf(
            rootProject.file("runtime/src/main/java"),
            generatedAssetsDir,
        )
    )
    resources.setSrcDirs(
        listOf(
            rootProject.file("runtime/src/main/resources"),
            mergedLangDir,
        )
    )
    resources.exclude("assets/$modId/lang/*.json")
}

sourceSets.named("test").configure {
    java.setSrcDirs(listOf(rootProject.file("runtime/src/test/kotlin")))
}

apply(from = rootProject.file("gradle/runtime-build-info.gradle.kts"))

tasks.named<ProcessResources>("processResources") {
    dependsOn("mergeLang")
    filesMatching(listOf("pack.mcmeta")) {
        expand(
            mapOf(
                "mod_name" to modName,
                "mod_id" to modId,
                "mod_version" to modVersion,
            )
        )
    }
    from(mergedLangDir) {
        into("assets/$modId/lang")
    }
}

tasks.named<KotlinCompile>("compileKotlin") {
    dependsOn("generateAssets")
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn("generateAssets")
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("dev-thin")
}

val engineScriptsDirectory = rootProject.file("runtime/src/main/resources/scripts")
val hasEngineScripts = engineScriptsDirectory.isDirectory &&
    engineScriptsDirectory.walkTopDown().any { it.isFile && it.extension == "kts" }
apply(from = rootProject.file("gradle/runtime-scripts.gradle.kts"))

val runtimeShadowJar = tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("dev")
    configurations = listOf(shadowBundle)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    includeEmptyDirs = false
    exclude("module-info.class")
    exclude("META-INF/versions/**/module-info.class")
    // kotlinx.serialization bundles into this jar, so the serialization compiler plugin reads THIS
    // jar's manifest to detect the runtime version when a script declares @Serializable. otherwise
    // it reports "version is unknown" and fails the script :(
    manifest {
        attributes(
            "Implementation-Version" to serializationVersion,
            "Require-Kotlin-Version" to kotlinVersion,
        )
    }
    eachFile {
        val sourcePath = file.toPath().toAbsolutePath().normalize()
        if (runtimeResourcesPath != null && sourcePath.startsWith(runtimeResourcesPath)) {
            exclude()
        }
    }
    if (hasEngineScripts) {
        from(engineScriptsDirectory) { into("scripts") }
        from(tasks.named("compileNamedEngineScripts")) {
            into("META-INF/hollowengine/scripts")
        }
        from(tasks.named("generateEngineScriptIndex"))
    }
}

val verifySerializationRuntimePackaging = tasks.register("verifySerializationRuntimePackaging") {
    group = JavaBasePlugin.VERIFICATION_GROUP
    description = "Verifies serialization metadata in the packaged runtime."
    dependsOn(runtimeShadowJar)
    inputs.file(runtimeShadowJar.flatMap { it.archiveFile })

    doLast {
        JarFile(runtimeShadowJar.get().archiveFile.get().asFile).use { archive ->
            check(archive.getEntry("kotlinx/serialization/KSerializer.class") != null) {
                "Packaged runtime does not contain kotlinx.serialization"
            }

            val attributes = checkNotNull(archive.manifest?.mainAttributes) {
                "Packaged runtime does not contain a manifest"
            }
            val expectedAttributes = mapOf(
                "Implementation-Version" to serializationVersion,
                "Require-Kotlin-Version" to kotlinVersion,
            )
            val mismatches = expectedAttributes.filter { (name, expected) ->
                attributes.getValue(name) != expected
            }
            check(mismatches.isEmpty()) {
                "Packaged runtime has invalid serialization metadata: $mismatches"
            }

            val moduleDescriptors = buildList {
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entryName = entries.nextElement().name
                    if (
                        entryName == "module-info.class" ||
                        entryName.startsWith("META-INF/versions/") &&
                        entryName.endsWith("/module-info.class")
                    ) {
                        add(entryName)
                    }
                }
            }
            check(moduleDescriptors.isEmpty()) {
                "Packaged runtime contains foreign JPMS descriptors: $moduleDescriptors"
            }
            check(!attributes.getValue("Multi-Release").equals("true", ignoreCase = true)) {
                "Packaged runtime must not activate dependency-owned multi-release descriptors"
            }
        }
    }
}

tasks.named("build") {
    dependsOn(verifySerializationRuntimePackaging)
}

tasks.named<RemapJarTask>("remapJar") {
    inputFile.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
    archiveClassifier.set("fabric")
}

tasks.matching { it.name == "transformProductionFabric" || it.name == "transformProductionNeoForge" }.configureEach {
    enabled = false
}

val embeddedRuntimeElements = configurations.create("embeddedRuntimeElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    isVisible = false

    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(runtimeMappingAttribute, "named")
    }

    outgoing.artifact(tasks.named<ShadowJar>("shadowJar"))
}

val embeddedFabricRuntimeElements = configurations.create("embeddedFabricRuntimeElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    isVisible = false

    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(runtimeMappingAttribute, "fabric")
    }

    outgoing.artifact(tasks.named<RemapJarTask>("remapJar"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xcollection-literals",
            "-Xexplicit-context-arguments",
            "-Xintrinsic-const-evaluation",
            "-Xskip-prerelease-check",
        )
    }
}

apply(from = rootProject.file("gradle/payload-remap.gradle.kts"))
