import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.language.jvm.tasks.ProcessResources
import java.util.Properties
import java.util.jar.JarFile

// Apply this script to the mod module. The addon is part of that mod's production jar.
// Everything is resolved next to this script, so the template folder can be renamed or moved.
val hollowAddonRoot = checkNotNull(buildscript.sourceFile) { "Apply the addon template from a file" }.parentFile
val hollowAddonSources = hollowAddonRoot.resolve("src/main/java")
val hollowAddonResources = hollowAddonRoot.resolve("src/main/resources")
val hollowAddonDescriptor = hollowAddonResources.resolve("META-INF/hollowengine/mod-addon.properties")
val hollowEngineDevJars = hollowAddonRoot.resolve("libs").listFiles { file ->
    file.isFile && file.name.matches(Regex("HollowEngine-.*-dev\\.jar"))
}.orEmpty().toList()
require(hollowAddonSources.isDirectory && hollowAddonDescriptor.isFile) {
    "HollowEngine addon template is incomplete: $hollowAddonRoot"
}
require(hollowEngineDevJars.size == 1) {
    "Expected one HollowEngine development JAR in ${hollowAddonRoot.resolve("libs")}; found ${hollowEngineDevJars.size}"
}

val hollowAddonProperties = Properties().apply { hollowAddonDescriptor.inputStream().use(::load) }
val hollowAddonEntry = hollowAddonProperties.getProperty("entry")?.trim().orEmpty()
require(hollowAddonEntry.contains('.') && hollowAddonEntry.substringAfterLast('.').isNotBlank()) {
    "Set entry to the addon's fully qualified class name in $hollowAddonDescriptor"
}
val hollowAddonEntryPath = hollowAddonEntry.replace('.', '/') + ".class"

fun Project.hollowHostModId(): String {
    val configured = listOf("modId", "mod_id").firstNotNullOfOrNull { key ->
        findProperty(key)?.toString()?.takeIf { it.isNotBlank() }
    }
    val fabric = file("src/main/resources/fabric.mod.json").takeIf { it.isFile }?.let { source ->
        (JsonSlurper().parse(source) as? Map<*, *>)?.get("id") as? String
    }
    val neoForge = file("src/main/resources/META-INF/neoforge.mods.toml").takeIf { it.isFile }?.let { source ->
        Regex("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"").find(source.readText())?.groupValues?.get(1)
    }
    return (configured ?: fabric ?: neoForge)
        ?.takeIf { it.matches(Regex("[a-z][a-z0-9_]*")) }
        ?: error("Cannot determine the host mod id. Set modId or mod_id in gradle.properties.")
}

val hollowHostModId = hollowHostModId()
extensions.getByType<SourceSetContainer>().named("main") {
    java.srcDir(hollowAddonSources)
    resources.srcDir(hollowAddonResources)
}
dependencies.add("compileOnly", files(hollowEngineDevJars.single()))
// These types appear in the addon API; keep them separate from the thin engine jar.
dependencies.add("compileOnly", "org.jetbrains.kotlinx:kotlinx-coroutines-core:${
    providers.gradleProperty("hollowEngineCoroutinesVersion").orElse("1.11.0").get()
}")
dependencies.add("compileOnly", "io.insert-koin:koin-core:${
    providers.gradleProperty("hollowEngineKoinVersion").orElse("4.1.1").get()
}")

tasks.named<ProcessResources>("processResources") {
    inputs.property("hollowAddonVersion", project.version.toString())
    inputs.property("hollowHostModId", hollowHostModId)
    filesMatching("META-INF/hollowengine/mod-addon.properties") {
        expand(mapOf("version" to project.version.toString(), "hostModId" to hollowHostModId))
    }
}

// Fabric Loom remaps the mod jar; NeoForge uses the ordinary production jar.
val hollowProductionTask = if (tasks.names.contains("remapJar")) "remapJar" else "jar"
val hollowProductionJar = tasks.named<AbstractArchiveTask>(hollowProductionTask)
val verifyHollowModAddon = tasks.register("verifyHollowModAddon") {
    group = "verification"
    description = "Checks that the installed mod jar contains its HollowEngine addon."
    dependsOn(hollowProductionJar)
    doLast {
        JarFile(hollowProductionJar.get().archiveFile.get().asFile).use { jar ->
            val descriptor = checkNotNull(jar.getJarEntry("META-INF/hollowengine/mod-addon.properties")) {
                "Mod jar is missing the embedded HollowEngine addon descriptor"
            }
            val properties = Properties().apply { jar.getInputStream(descriptor).use(::load) }
            check(properties.getProperty("hostModId") == hollowHostModId)
            check(properties.getProperty("version") == project.version.toString())
            check(jar.getJarEntry(hollowAddonEntryPath) != null) {
                "Addon entrypoint $hollowAddonEntryPath is absent from the production mod jar"
            }
        }
    }
}
tasks.named("assemble") { dependsOn(verifyHollowModAddon) }
