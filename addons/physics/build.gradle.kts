base {
    archivesName.set("HollowEnginePhysics")
}

val joltVersion = "6.0.0"

fun DependencyHandler.addonBootstrapLibrary(notation: String) {
    add("addonBootstrapLibraries", notation)
}

dependencies {
    // The Java side is the same in every platform artifact, only the natives differ.
    addonBootstrapLibrary("com.github.stephengold:jolt-jni-Windows64:$joltVersion")

    listOf(
        "Windows64",
        "Linux64",
        "Linux_ARM64",
        "MacOSX64",
        "MacOSX_ARM64",
    ).forEach { platform ->
        addonBootstrapLibrary("com.github.stephengold:jolt-jni-$platform:$joltVersion:ReleaseDp@jar")
    }

    val serializationVersion = rootProject.property("serializationVersion") as String
    add("testRuntimeOnly", "org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
}
