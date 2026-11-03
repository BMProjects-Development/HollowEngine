base.archivesName.set("HollowEngineLighting")

sourceSets.main { java.srcDir("src/irl/java") }

dependencies {
    add("modCompileOnly", "lib:iris-fabric:1.8.14-beta.1+mc1.21.1-devpatch")
    val serializationVersion = rootProject.property("serializationVersion") as String
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

