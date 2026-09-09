package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeAnimatorDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeImageDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeRigDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeSoundsDocument

internal const val SoundsFileName = "sounds.json"

internal fun HollowIdeFileTypeRegistry.registerBuiltinFileTypes(
    modelEditor: HollowIdeFileEditor,
    imageEditor: HollowIdeFileEditor,
    videoEditor: HollowIdeFileEditor,
    soundsEditor: HollowIdeFileEditor,
    animatorEditor: HollowIdeFileEditor,
    rigEditor: HollowIdeFileEditor,
    textEditor: HollowIdeFileEditor,
) {
    register(
        HollowIdeFileType.extensions(
            id = "rig",
            extensions = listOf(".rig"),
            priority = 270,
            loader = { _, bytes -> HollowIdeRigDocument(bytes) },
            editor = rigEditor,
        ),
    )
    register(
        HollowIdeFileType.extensions(
            id = "animator",
            extensions = listOf(".animator"),
            priority = 260,
            loader = { _, bytes -> HollowIdeAnimatorDocument(bytes) },
            editor = animatorEditor,
        ),
    )
    register(
        HollowIdeFileType(
            id = "sounds",
            priority = 250,
            matcher = { path, _ -> path.isSoundsFile() },
            pathMatcher = { path -> path.isSoundsFile() },
            loader = { _, bytes -> HollowIdeSoundsDocument(bytes) },
            editor = soundsEditor,
        ),
    )
    register(
        HollowIdeFileType.extensions(
            id = "model",
            extensions = listOf(".gltf", ".glb", ".fbx", ".geo.json"),
            priority = 200,
            requiresContent = false,
            loader = { _, _ -> HollowIdeReadOnlyDocument },
            editor = modelEditor,
        ),
    )
    register(
        HollowIdeFileType.extensions(
            id = "video",
            extensions = listOf(".mp4"),
            priority = 150,
            requiresContent = false,
            loader = { _, _ -> HollowIdeReadOnlyDocument },
            editor = videoEditor,
        ),
    )
    register(
        HollowIdeFileType.extensions(
            id = "image",
            extensions = listOf(".png", ".jpg", ".jpeg"),
            priority = 100,
            loader = ::HollowIdeImageDocument,
            editor = imageEditor,
        ),
    )
    register(
        HollowIdeFileType.fallback(
            id = BuiltinTextFileTypeId,
            matcher = { _, bytes -> bytes.isProbablyText() },
            loader = { _, bytes -> HollowIdeTextDocument(bytes.toString(Charsets.UTF_8)) },
            editor = textEditor,
        ),
    )
}

private fun String.isSoundsFile(): Boolean =
    substringAfterLast('/').equals(SoundsFileName, ignoreCase = true)
