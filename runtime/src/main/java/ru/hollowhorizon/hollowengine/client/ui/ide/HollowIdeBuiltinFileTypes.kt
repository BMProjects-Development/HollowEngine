package ru.hollowhorizon.hollowengine.client.ui.ide

import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeAnimatorDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeImageDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeRigDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeShaderGraphDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeVfxDocument

internal fun HollowIdeFileTypeRegistry.registerBuiltinFileTypes(
    modelEditor: HollowIdeFileEditor,
    imageEditor: HollowIdeFileEditor,
    videoEditor: HollowIdeFileEditor,
    animatorEditor: HollowIdeFileEditor,
    rigEditor: HollowIdeFileEditor,
    vfxEditor: HollowIdeFileEditor,
    shaderGraphEditor: HollowIdeFileEditor,
    textEditor: HollowIdeFileEditor,
) {
    register(
        HollowIdeFileType.extensions(
            id = "material",
            extensions = listOf(".material"),
            priority = 285,
            loader = { _, bytes -> HollowIdeShaderGraphDocument(bytes) },
            editor = shaderGraphEditor,
        ),
    )
    register(
        HollowIdeFileType.extensions(
            id = "vfx",
            extensions = listOf(".vfx"),
            priority = 280,
            loader = { _, bytes -> HollowIdeVfxDocument(bytes) },
            editor = vfxEditor,
        ),
    )
    register(
        HollowIdeFileType.extensions(
            id = "rig",
            extensions = listOf(".rig"),
            priority = 270,
            loader = { path, bytes -> HollowIdeRigDocument(bytes, path.substringAfter("assets/").replaceFirst("/", ":").removeSuffix(".rig")) },
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
