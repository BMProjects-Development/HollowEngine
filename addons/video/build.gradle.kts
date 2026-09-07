base {
    archivesName.set("HollowEngineVideo")
}

val ffmpegPresetVersion = "8.0.1-1.5.13"

fun DependencyHandler.addonBootstrapLibrary(notation: String) {
    add("addonBootstrapLibraries", notation)
}

dependencies {
    addonBootstrapLibrary("org.bytedeco:ffmpeg:$ffmpegPresetVersion")
    addonBootstrapLibrary("org.bytedeco:ffmpeg:$ffmpegPresetVersion:windows-x86_64")
    addonBootstrapLibrary("org.bytedeco:ffmpeg:$ffmpegPresetVersion:linux-x86_64")
    addonBootstrapLibrary("org.bytedeco:ffmpeg:$ffmpegPresetVersion:linux-arm64")
    addonBootstrapLibrary("org.bytedeco:ffmpeg:$ffmpegPresetVersion:macosx-x86_64")
    addonBootstrapLibrary("org.bytedeco:ffmpeg:$ffmpegPresetVersion:macosx-arm64")
}
