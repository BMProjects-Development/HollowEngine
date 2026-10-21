base {
    archivesName.set("HollowEngineNarrative")
}

dependencies {
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    val serializationVersion = rootProject.property("serializationVersion") as String
    add("testRuntimeOnly", "org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
