import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.gradle.process.CommandLineArgumentProvider

val engineScriptsDirectory = rootProject.file("runtime/src/main/resources/scripts")
val engineScriptFiles = fileTree(engineScriptsDirectory) { include("**/*.kts") }

if (!engineScriptFiles.isEmpty) evaluationDependsOn(":addons:compiler")

val kotlinVersion = rootProject.property("kotlinVersion") as String
val modVersion = property("modVersion") as String
val sourceSets = extensions.getByType<SourceSetContainer>()

/**
 * Compiles the engine's own scripts against Mojang names. On Fabric the bootstrap remaps them together
 * with the rest of the engine jar, from the same table.
 */
fun registerEngineScriptCompilation(): TaskProvider<JavaExec> {
    val outputDirectory = layout.buildDirectory.dir("hollowengine/scripts/named")
    val compilerProject = rootProject.project(":addons:compiler")
    val toolClasspath = configurations.maybeCreate("hollowengineScriptCompiler").apply {
        isCanBeResolved = true
        isCanBeConsumed = false
        isTransitive = true
    }
    dependencies.add(
        toolClasspath.name,
        files(compilerProject.tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }),
    )
    listOf(
        "org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion",
        "org.jetbrains.kotlin:kotlin-metadata-jvm:$kotlinVersion",
        "org.apache.logging.log4j:log4j-core:2.23.1",
        "org.ow2.asm:asm-commons:9.7.1",
    ).forEach { notation -> dependencies.add(toolClasspath.name, notation) }

    val mainSources = sourceSets.named("main")
    return tasks.register<JavaExec>("compileNamedEngineScripts") {
        group = "build"
        description = "Compiles the engine's own scripts against Mojang names."
        mainClass.set("ru.hollowhorizon.hollowengine.common.compiler.tools.ScriptPrecompiler")
        classpath = files(
            mainSources.map { it.output },
            mainSources.map { it.compileClasspath },
            mainSources.map { it.runtimeClasspath },
            toolClasspath,
        )
        workingDir = layout.buildDirectory.dir("hollowengine/precompiler").get().asFile
        inputs.files(engineScriptFiles).withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(outputDirectory)
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "--scripts", engineScriptsDirectory.absolutePath,
                "--output", outputDirectory.get().asFile.absolutePath,
                "--namespace", "hollowengine",
                "--fingerprint", modVersion,
                "--identity", "neoforge/official/production",
                "--remap", "false",
                "--mappings", "",
            )
        })
        doFirst {
            workingDir.mkdirs()
        }
    }
}

val generateEngineScriptIndex = tasks.register("generateEngineScriptIndex") {
    val outputDirectory = layout.buildDirectory.dir("hollowengine/script-index")
    inputs.files(engineScriptFiles).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(outputDirectory)
    doLast {
        val index = engineScriptFiles.files
            .map { engineScriptsDirectory.toPath().relativize(it.toPath()).toString().replace('\\', '/') }
            .sorted()
        val target = outputDirectory.get().asFile.resolve("META-INF/hollowengine/scripts.index")
        target.parentFile.mkdirs()
        target.writeText(index.joinToString("\n"))
    }
}

if (!engineScriptFiles.isEmpty) {
    registerEngineScriptCompilation()
}
