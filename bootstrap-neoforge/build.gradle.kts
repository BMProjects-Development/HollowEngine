import java.security.MessageDigest

plugins {
    id("architectury-plugin")
    id("dev.architectury.loom")
    id("com.gradleup.shadow")
}

val modId: String by properties
val modName: String by properties
val modVersion: String by properties
val modAuthor: String by rootProject.properties
val license: String by properties
val modGroup: String by properties
val minecraftVersion: String by rootProject.properties
val parchmentVersion: String by rootProject.properties
val neoForgeVersion: String by rootProject.properties

group = modGroup
version = modVersion
base.archivesName.set("$modName-neoforge-$minecraftVersion")

val sourceSets = extensions.getByType<SourceSetContainer>()
val embeddedRuntimeDir = layout.buildDirectory.dir("generated/embedded-runtime")
val runtimePayloadNotice = rootProject.file("bootstrap/runtime-payload/README.MD")
val generatedMetadataDir = layout.buildDirectory.dir("generated/mod-metadata")
val mergedRuntimeLangDir = rootProject.layout.projectDirectory.dir("build/runtime/generated/lang/")
val runtimeMappingAttribute = Attribute.of("hollowengine.runtime.mapping", String::class.java)

/**
 * NeoForge runs on Mojang names in development and in production alike, so its mixin configs have no
 * reference map. The config templates shared with Fabric name one; the line is dropped here.
 */
fun withoutRefmap(line: String): String? = line.takeUnless { it.trimStart().startsWith("\"refmap\"") }

/** Filters are not task inputs; this stands in for [withoutRefmap], so changing both reruns the tasks. */
val mixinRefmapRule = "without-refmap"

architectury {
    platformSetupLoomIde()
    neoForge()
}

loom {
    silentMojangMappingsLicense()

    val accessWidener = rootProject.file("runtime/src/main/resources/$modId.accesswidener")
    if (accessWidener.exists()) {
        accessWidenerPath.set(accessWidener)
    }

    runs {
        configureEach {
            runDir("../run")
            if (name == "client") {
                programArgs("--username", "TheHollowHorizon")
            }
        }
    }

    settingsPostEdit.add {
        it.runDirIdeaUrl = "file://\$PROJECT_DIR\$/run"
    }
}

configurations {
    create("common") {
        isCanBeResolved = true
        isCanBeConsumed = false
    }
    named("compileClasspath") {
        extendsFrom(getByName("common"))
    }
    named("runtimeClasspath") {
        extendsFrom(getByName("common"))
    }
    named("developmentNeoForge") {
        extendsFrom(getByName("common"))
    }
    create("shadowBundle") {
        isCanBeResolved = true
        isCanBeConsumed = false
    }
}

val embeddedRuntime by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(runtimeMappingAttribute, "named")
    }
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
    maven("https://maven.architectury.dev/")
    maven("https://repo.spongepowered.org/repository/maven-public/")
    mavenLocal()
    flatDir { dirs(rootProject.file("libs")) }
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-$minecraftVersion:$parchmentVersion")
    })

    neoForge("net.neoforged:neoforge:$neoForgeVersion")

    implementation("lib:iris-neoforge:1.8.12+mc1.21.1")
    runtimeOnly("lib:iris-neoforge:1.8.12+mc1.21.1")

    implementation("lib:sodium-neoforge:0.6.13+mc1.21.1")
    runtimeOnly("lib:sodium-neoforge:0.6.13+mc1.21.1")

    implementation("org.anarres:jcpp:1.4.14")
    implementation("io.github.douira:glsl-transformer:2.0.1")

    val mixinExtras = "io.github.llamalad7:mixinextras-neoforge:0.4.1"
    implementation(mixinExtras)
    include(mixinExtras)

    add("embeddedRuntime", project(path = ":runtime", configuration = "embeddedRuntimeElements"))
    "common"(project(path = ":bridge", configuration = "namedElements")) { isTransitive = false }
    "shadowBundle"(project(path = ":bridge", configuration = "transformProductionNeoForge")) { isTransitive = false }
}

val embedRuntimeJar = tasks.register("embedRuntimeJar") {
    group = "build"
    description = "Embeds the isolated runtime jar into bootstrap resources."

    inputs.files(embeddedRuntime)
    inputs.file(runtimePayloadNotice)
    outputs.dir(embeddedRuntimeDir)

    doLast {
        val outputDir = embeddedRuntimeDir.get().dir("META-INF/hollowengine/runtime").asFile
        outputDir.mkdirs()

        val runtimeJar = embeddedRuntime.singleFile
        val targetJar = outputDir.resolve("HollowEngineRuntime.jar")
        runtimeJar.copyTo(targetJar, overwrite = true)

        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(targetJar.readBytes())
            .joinToString("") { "%02x".format(it) }
        outputDir.resolve("HollowEngineRuntime.sha256").writeText(sha256)

        runtimePayloadNotice.copyTo(outputDir.resolve("README.MD"), overwrite = true)
    }
}

val generateNeoForgeModMetadata = tasks.register<ProcessResources>("generateNeoForgeModMetadata") {
    group = "build"
    description = "Generates the expanded neoforge.mods.toml for IDE and Loom consumption."

    val properties = mapOf(
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_version" to modVersion,
        "mod_author" to modAuthor,
        "license" to license,
        "minecraft_version" to minecraftVersion,
        "neo_version" to neoForgeVersion,
    )

    inputs.properties(properties)
    inputs.property("mixinRefmap", mixinRefmapRule)
    from(rootProject.file("bootstrap-neoforge/src/main/templates"))
    from(rootProject.file("bridge/src/main/resources/hollowengine.bridge.mixins.json")) {
        rename { "hollowengine-neoforge.bridge.mixins.json" }
    }
    into(generatedMetadataDir)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand(properties)
    }
    filesMatching("hollowengine-neoforge.bridge.mixins.json") {
        filter(::withoutRefmap)
    }
}

sourceSets.named("main").configure {
    java.setSrcDirs(
        listOf(
            rootProject.file("bootstrap/src/main/java"),
            rootProject.file("bootstrap-neoforge/src/main/java"),
        )
    )
    java.exclude(
        "ru/hollowhorizon/hollowengine/bootstrap/fabric/**",
        "ru/hollowhorizon/hollowengine/bootstrap/forge/**",
        "ru/hollowhorizon/hollowengine/bootstrap/mixins/fabric/**",
    )
    resources.setSrcDirs(
        listOf(
            rootProject.file("bootstrap/src/main/resources"),
            rootProject.file("bootstrap-neoforge/src/main/resources"),
            rootProject.file("runtime/src/main/resources"),
            generatedMetadataDir,
            mergedRuntimeLangDir,
            embeddedRuntimeDir,
        )
    )
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(embedRuntimeJar, generateNeoForgeModMetadata, ":runtime:mergeLang")
    inputs.property("mixinRefmap", mixinRefmapRule)
    filesMatching(listOf("*.mixins.json", "pack.mcmeta")) {
        filter(::withoutRefmap)
        expand(
            mapOf(
                "mod_id" to modId,
                "mod_name" to modName,
                "mod_version" to modVersion,
                "mod_author" to modAuthor,
                "license" to license,
                "minecraft_version" to minecraftVersion,
                "neo_version" to neoForgeVersion,
            )
        )
    }
}

tasks.named("classes") {
    dependsOn(embedRuntimeJar)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    enabled = false
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    configurations = listOf(project.configurations.getByName("shadowBundle"))
    archiveClassifier.set("dev-shadow")
    exclude("hollowengine.bridge.refmap.json")
}

val bootstrapDevJar = tasks.register<Jar>("bootstrapDevJar") {
    group = "build"
    description = "Packages the shaded bootstrap jar with the isolated runtime payload."

    dependsOn("classes", embedRuntimeJar, ":bridge:transformProductionNeoForge")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set(base.archivesName.get())
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("dev")

    from(sourceSets.named("main").map { it.output })
    from({
        project.configurations.getByName("shadowBundle")
            .map { zipTree(it) }
    }) {
        exclude("hollowengine.bridge.mixins.json", "hollowengine.bridge.refmap.json")
    }
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    inputFile.set(bootstrapDevJar.flatMap { it.archiveFile })
    atAccessWideners.set(listOf("$modId.accesswidener"))
}

tasks.named<JavaCompile>("compileJava") {
    setSource(sourceSets.named("main").get().java)
}

tasks.matching { it.name.startsWith("run") }.configureEach {
    dependsOn("classes")
}
