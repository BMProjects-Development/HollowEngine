// Applied to the bootstrap of a loader. Lets an addon compile its src/<loader> against the loader's API
// exactly as this project sees it: the game with the loader's patches, the loader and its libraries.
// Another project may not resolve this one's classpath, so the classpath is handed over as a list.
val addonPlatformApiList = layout.buildDirectory.file("addon-platform-api/classpath.txt")

val listAddonPlatformApi = tasks.register("listAddonPlatformApi") {
    group = "build"
    description = "Lists the compile classpath of this loader for the addons that have code for it."
    val classpath = configurations.named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME)
    inputs.files(classpath)
    outputs.file(addonPlatformApiList)
    doLast {
        addonPlatformApiList.get().asFile.writeText(classpath.get().files.joinToString("\n") { it.absolutePath })
    }
}

configurations.create("addonPlatformApiElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(addonPlatformApiList) { builtBy(listAddonPlatformApi) }
}
