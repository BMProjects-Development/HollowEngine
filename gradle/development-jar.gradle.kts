import org.gradle.jvm.tasks.Jar
import java.util.jar.JarFile

val modName = property("modName") as String
val modVersion = property("modVersion") as String
val minecraftVersion = property("minecraftVersion") as String

val developmentJar = tasks.register<Jar>("developmentJar") {
    group = "build"
    description = "Packages the named engine API without bundled dependencies for compiling external addons."
    archiveBaseName.set("$modName-$minecraftVersion")
    archiveVersion.set(modVersion)
    archiveClassifier.set("dev")
    destinationDirectory.set(layout.buildDirectory.dir("development"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    includeEmptyDirs = false

    // Use the thin jar: dependencies must be resolved by the consuming project.
    val runtimeJar = project(":runtime").tasks.named<Jar>("jar")
    val bridgeJar = project(":bridge").tasks.named<Jar>("jar")
    dependsOn(runtimeJar, bridgeJar)
    from(runtimeJar.flatMap { it.archiveFile }.map { zipTree(it.asFile) })
    from(bridgeJar.flatMap { it.archiveFile }.map { zipTree(it.asFile) })
    from(rootProject.file("LICENSE.MD"))
    // Keep the engine's Kotlin package metadata for top-level functions and type aliases.
    include(
        "ru/hollowhorizon/hollowengine/**/*.class", "META-INF/*.kotlin_module", "LICENSE.MD",
    )

    manifest.attributes(
        "HollowEngine-Version" to modVersion,
        "HollowEngine-Mappings" to "mojang",
    )

    doLast {
        JarFile(archiveFile.get().asFile).use { archive ->
            val required = listOf(
                "ru/hollowhorizon/hollowengine/common/addons/HollowAddonEntrypoint.class",
                "ru/hollowhorizon/hollowengine/common/addons/HollowAddonContext.class",
                "ru/hollowhorizon/hollowengine/common/addons/HollowAddonApiKt.class",
                "ru/hollowhorizon/hollowengine/client/ui/screen/HollowComposeUiScreen.class",
                "ru/hollowhorizon/hollowengine/bootstrap/runtime/RuntimeBridge.class",
            )
            val missing = required.filter { archive.getEntry(it) == null }
            check(missing.isEmpty()) { "Development jar is missing compile-time API classes: $missing" }
            check(archive.entries().asSequence().any { it.name.endsWith(".kotlin_module") }) {
                "Development jar is missing Kotlin package metadata"
            }
            val bundledDependencies = archive.entries().asSequence()
                .map { it.name }
                .filter {
                    it.endsWith(".class") && !it.startsWith("ru/hollowhorizon/hollowengine/") ||
                        it.endsWith(".kotlin_builtins") || it.endsWith(".jar")
                }
                .toList()
            check(bundledDependencies.isEmpty()) {
                "Development jar must not bundle dependency classes or jars: $bundledDependencies"
            }
            check(archive.getEntry("fabric.mod.json") == null && archive.getEntry("META-INF/neoforge.mods.toml") == null) {
                "Development jar must be a compile-only library"
            }
        }
    }
}

tasks.named("assemble") {
    dependsOn(developmentJar)
}

tasks.named<Sync>("buildAndCollect") {
    from(developmentJar)
}
