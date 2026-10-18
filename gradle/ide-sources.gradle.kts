import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.JvmLibrary
import org.gradle.language.base.artifact.SourcesArtifact

val sourceRootsFile = layout.buildDirectory.file("ide/source-roots.txt")
val sourceManifests = files()

subprojects {
    val sourceProject = this
    plugins.withId("java") {
        val manifest = sourceProject.layout.buildDirectory.file("ide/source-roots.txt")
        val collect = sourceProject.tasks.register("collectIdeSourceRoots") {
            group = "ide"
            description = "Records this module's sources and available dependency source artifacts."
            outputs.file(manifest)
            outputs.upToDateWhen { false }
            doLast {
                val directories = sourceProject.extensions.getByType<SourceSetContainer>()
                    .getByName("main").allSource.srcDirs.filter { it.isDirectory }
                val classpath = sourceProject.configurations.getByName("compileClasspath")
                val components = classpath.incoming.resolutionResult.allComponents
                    .mapNotNull { it.id as? ModuleComponentIdentifier }
                val sources = sourceProject.dependencies.createArtifactResolutionQuery().forComponents(components)
                    .withArtifacts(JvmLibrary::class.java, SourcesArtifact::class.java).execute()
                    .resolvedComponents.flatMap { component ->
                        component.getArtifacts(SourcesArtifact::class.java).filterIsInstance<ResolvedArtifactResult>().map { it.file }
                    }
                val output = manifest.get().asFile
                output.parentFile.mkdirs()
                val mappedSources = classpath.files.map { it.resolveSibling("${it.nameWithoutExtension}-sources.jar") }
                    .filter { it.isFile }
                val gameSources = sourceProject.tasks.matching { it.name.startsWith("genSourcesWith") }
                    .flatMap { it.outputs.files.files }.filter { it.isFile && it.name.endsWith("-sources.jar") }
                output.writeText((directories + mappedSources + gameSources + sources).map { it.absolutePath }.distinct().joinToString("\n"))
            }
        }
        sourceManifests.from(collect)
        plugins.withId("dev.architectury.loom") {
            collect.configure { mustRunAfter(sourceProject.tasks.matching { it.name.startsWith("genSourcesWith") }) }
        }
    }
    plugins.withId("dev.architectury.loom") {
        tasks.matching {
            it.name.startsWith("run") || it.name.startsWith("configure") && it.name.endsWith("Launch") ||
                it.name in setOf("ideaSyncTask", "genEclipseRuns", "vscode")
        }.configureEach {
            dependsOn(":writeIdeSourceRoots")
        }
        tasks.matching { it.name.startsWith("genSourcesWith") }.configureEach {
            finalizedBy(":writeIdeSourceRoots")
        }
    }
}

tasks.register("writeIdeSourceRoots") {
    group = "ide"
    description = "Collects source roots for the in-game IDE's development launches."
    inputs.files(sourceManifests)
    outputs.file(sourceRootsFile)
    outputs.upToDateWhen { false }
    doLast {
        val roots = sourceManifests.files.flatMap { it.readLines() }
        val output = sourceRootsFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(roots.filter(String::isNotBlank).distinct().joinToString("\n"))
    }
}
