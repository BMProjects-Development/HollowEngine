import org.gradle.jvm.tasks.Jar

plugins {
    java
    id("architectury-plugin")
    id("dev.architectury.loom")
}

val modId: String by properties
val modVersion: String by properties
val modGroup: String by properties
val minecraftVersion: String by rootProject.properties
val enabledPlatforms = (rootProject.property("enabledPlatforms") as String).split(',').map(String::trim).toTypedArray()
val fabricLoaderVersion: String by rootProject.properties
val parchmentVersion: String by rootProject.properties

group = modGroup
version = modVersion
base.archivesName.set("HollowEngineBridge")

val sourceSets = extensions.getByType<SourceSetContainer>()

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.architectury.dev/")
    maven("https://maven.parchmentmc.org")
    maven("https://repo.spongepowered.org/repository/maven-public/")
    mavenLocal()
    flatDir { dirs(rootProject.file("libs")) }
}

architectury {
    common(*enabledPlatforms)
}

loom {
    silentMojangMappingsLicense()
    mixin.useLegacyMixinAp.set(true)
    mixin.add(sourceSets.named("main").get(), "$modId.bridge.refmap.json")
}

dependencies {
    "minecraft"("com.mojang:minecraft:$minecraftVersion")
    "mappings"(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-$minecraftVersion:$parchmentVersion")
    })

    modImplementation("lib:iris-fabric:1.8.8+mc1.21.1")
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")

    compileOnly("org.spongepowered:mixin:0.8.7")
    compileOnly("io.github.llamalad7:mixinextras-common:0.4.1")
    compileOnly("org.jetbrains:annotations:24.1.0")
}

sourceSets.named("main").configure {
    java.setSrcDirs(listOf(rootProject.file("bridge/src/main/java")))
    resources.setSrcDirs(listOf(rootProject.file("bridge/src/main/resources")))
}

tasks.named<ProcessResources>("processResources") {
    filesMatching("hollowengine.bridge.mixins.json") {
        expand("refmap" to "$modId.bridge.refmap.json")
    }
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("")
}
