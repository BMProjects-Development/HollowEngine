import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.jvm.tasks.Jar
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
val fabricLoaderVersion: String by rootProject.properties
val fabricApiVersion: String by rootProject.properties

group = modGroup
version = modVersion
base.archivesName.set("$modName-fabric-$minecraftVersion")

val sourceSets = extensions.getByType<SourceSetContainer>()
val embeddedRuntimeDir = layout.buildDirectory.dir("generated/embedded-runtime")
val runtimePayloadNotice = rootProject.file("bootstrap/runtime-payload/README.MD")
val embeddedRemapTableDir = layout.buildDirectory.dir("generated/embedded-remap-table")
val generatedMetadataDir = layout.buildDirectory.dir("generated/mod-metadata")
val mergedRuntimeLangDir = rootProject.layout.projectDirectory.dir("build/runtime/generated/lang/")
val runtimeMappingAttribute = Attribute.of("hollowengine.runtime.mapping", String::class.java)
val bootstrapRefmap = "$modId-fabric.refmap.json"

architectury {
    platformSetupLoomIde()
    fabric()
}

loom {
    silentMojangMappingsLicense()

    val accessWidener = rootProject.file("runtime/src/main/resources/$modId.accesswidener")
    if (accessWidener.exists()) {
        accessWidenerPath.set(accessWidener)
    }

    mixin.useLegacyMixinAp.set(true)
    mixin.add(sourceSets.named("main").get(), "$modId-fabric.refmap.json")

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
    named("developmentFabric") {
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

val payloadRemapTable by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
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

    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    val fabricApi = "net.fabricmc.fabric-api:fabric-api:$fabricApiVersion"
    modImplementation(fabricApi)
    include(fabricApi)
    modImplementation("lib:iris-fabric:1.8.8+mc1.21.1")
    modImplementation("lib:sodium-fabric:0.6.13+mc1.21.1")
    val mixinExtras = "io.github.llamalad7:mixinextras-fabric:0.4.1"
    modImplementation(mixinExtras)
    include(mixinExtras)

    implementation("org.anarres:jcpp:1.4.14")
    implementation("io.github.douira:glsl-transformer:2.0.1")

    add("embeddedRuntime", project(path = ":runtime", configuration = "embeddedRuntimeElements"))
    add("payloadRemapTable", project(path = ":runtime", configuration = "payloadRemapTableElements"))
    "common"(project(path = ":bridge", configuration = "namedElements")) { isTransitive = false }
    "shadowBundle"(project(path = ":bridge", configuration = "transformProductionFabric")) { isTransitive = false }
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

val embedRemapTable = tasks.register("embedRemapTable") {
    group = "build"
    description = "Embeds Fabric remap table next to the runtime payload."

    inputs.files(payloadRemapTable)
    outputs.dir(embeddedRemapTableDir)

    doLast {
        val outputDir = embeddedRemapTableDir.get().dir("META-INF/hollowengine/runtime").asFile
        outputDir.mkdirs()
        payloadRemapTable.singleFile.copyTo(outputDir.resolve("payload-remap-fabric.tbl.gz"), overwrite = true)
    }
}

val generateFabricModMetadata = tasks.register<ProcessResources>("generateFabricModMetadata") {
    group = "build"
    description = "Generates the expanded fabric.mod.json for IDE and Loom consumption."

    val properties = mapOf(
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_version" to modVersion,
        "mod_author" to modAuthor,
        "license" to license,
        "minecraft_version" to minecraftVersion,
        "fabric_loader_version" to fabricLoaderVersion,
    )

    inputs.properties(properties)
    from(rootProject.file("bootstrap-fabric/src/main/templates"))
    into(generatedMetadataDir)
    filesMatching("fabric.mod.json") {
        expand(properties)
    }
}

sourceSets.named("main").configure {
    java.setSrcDirs(
        listOf(
            rootProject.file("bootstrap/src/main/java"),
            rootProject.file("bootstrap-fabric/src/main/java"),
        )
    )
    resources.setSrcDirs(
        listOf(
            rootProject.file("bootstrap/src/main/resources"),
            rootProject.file("bootstrap-fabric/src/main/resources"),
            rootProject.file("runtime/src/main/resources"),
            generatedMetadataDir,
            mergedRuntimeLangDir,
            embeddedRuntimeDir,
        )
    )
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(embedRuntimeJar, generateFabricModMetadata, ":runtime:mergeLang")
    filesMatching(listOf("*.mixins.json", "pack.mcmeta")) {
        expand(
            mapOf(
                "mod_id" to modId,
                "mod_name" to modName,
                "mod_version" to modVersion,
                "mod_author" to modAuthor,
                "license" to license,
                "minecraft_version" to minecraftVersion,
                "fabric_loader_version" to fabricLoaderVersion,
                "refmap" to bootstrapRefmap,
            )
        )
    }
    exclude("META-INF/neoforge.mods.toml")
}

tasks.named("classes") {
    dependsOn(embedRuntimeJar)
}

tasks.named<ShadowJar>("shadowJar") {
    enabled = false
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    configurations = listOf(project.configurations.getByName("shadowBundle"))
    archiveClassifier.set("dev-shadow")
}

val bootstrapDevJar = tasks.register<Jar>("bootstrapDevJar") {
    group = "build"
    description = "Packages the shaded bootstrap jar with the isolated runtime payload."

    dependsOn("classes", embedRuntimeJar, ":bridge:transformProductionFabric")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set(base.archivesName.get())
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("dev")

    from(sourceSets.named("main").map { it.output })
    from({ project.configurations.getByName("shadowBundle").map { zipTree(it) } })
}

val bootstrapProductionJar = tasks.register<Jar>("bootstrapProductionJar") {
    group = "build"
    description = "Packages the production bootstrap jar with the remapped isolated runtime payload."

    dependsOn("classes", embedRemapTable, ":bridge:transformProductionFabric")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set(base.archivesName.get())
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("production-dev")

    from(sourceSets.named("main").map { it.output })
    from(embeddedRemapTableDir)
    from({ project.configurations.getByName("shadowBundle").map { zipTree(it) } })
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    inputFile.set(bootstrapProductionJar.flatMap { it.archiveFile })
}

tasks.named<JavaCompile>("compileJava") {
    setSource(sourceSets.named("main").get().java)
}

tasks.matching { it.name.startsWith("run") }.configureEach {
    dependsOn("classes")
}
