import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile

val generatedBuildInfoDirectory = layout.buildDirectory.dir("generated/sources/buildinfo/kotlin")
val engineVersion = property("modVersion") as String
val gameVersion = rootProject.property("minecraftVersion") as String
val languageVersion = rootProject.property("kotlinVersion") as String
val loaderRelocation = rootProject.property("fabricRelocation") as String

// Compiled scripts are bytecode against this exact engine, so the cache key has to name the build it
// was produced by. Generating a constant keeps that information available without reading resources.
val generateBuildInfo = tasks.register("generateBuildInfo") {
    inputs.property("modVersion", engineVersion)
    inputs.property("minecraftVersion", gameVersion)
    inputs.property("kotlinVersion", languageVersion)
    inputs.property("fabricRelocation", loaderRelocation)
    outputs.dir(generatedBuildInfoDirectory)
    doLast {
        val target = generatedBuildInfoDirectory.get().asFile
            .resolve("ru/hollowhorizon/hollowengine/HollowEngineBuild.kt")
        target.parentFile.mkdirs()
        target.writeText(
            """
            package ru.hollowhorizon.hollowengine

            /** Generated from the Gradle build. Do not edit. */
            object HollowEngineBuild {
                const val VERSION = "$engineVersion"
                const val MINECRAFT_VERSION = "$gameVersion"
                const val KOTLIN_VERSION = "$languageVersion"

                /** Packages of the shared bootstrap that Fabric sees under other names, as `from=to,...`. */
                const val FABRIC_RELOCATION = "$loaderRelocation"
            }
            """.trimIndent() + "\n"
        )
    }
}

extensions.getByType<SourceSetContainer>().named("main") {
    java.srcDir(generatedBuildInfoDirectory)
}

tasks.named("compileKotlin") {
    dependsOn(generateBuildInfo)
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateBuildInfo)
}
