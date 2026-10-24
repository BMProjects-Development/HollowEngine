base {
    archivesName.set("HollowEngineNarrative")
}

dependencies {
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    val serializationVersion = rootProject.property("serializationVersion") as String
    add("testImplementation", "org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")

    val kotlinVersion = rootProject.property("kotlinVersion") as String
    val classGraph = "io.github.classgraph:classgraph:4.8.173"
    compileOnly("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    compileOnly(classGraph)
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    testImplementation(classGraph)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
