package ru.hollowhorizon.hollowengine.client.ui.ide.asset

import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.ui.ide.*
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeImageDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.sounds.AudioFileExtensions

internal const val AssetImageFileTypeId = "asset-image"
internal const val AssetTextFileTypeId = "asset-text"
internal const val AssetJsonModelFileTypeId = "asset-json-model"
internal const val AssetAudioFileTypeId = "asset-audio"

internal fun assetFileTypeId(
    scope: AssetResourceScope,
    path: String,
    bytes: ByteArray,
    forceText: Boolean = false,
): String? = when {
    forceText && bytes.isProbablyText() -> AssetTextFileTypeId
    forceText -> null
    scope == AssetResourceScope.CLIENT && path.isVanillaJsonModel() -> AssetJsonModelFileTypeId
    scope == AssetResourceScope.CLIENT && HollowModelManager.supports(path) -> "model"
    scope == AssetResourceScope.CLIENT && path.isAudio() -> AssetAudioFileTypeId
    path.isImage() -> AssetImageFileTypeId
    bytes.isProbablyText() -> AssetTextFileTypeId
    else -> null
}

internal fun HollowIdeFileTypeRegistry.registerAssetFileTypes(
    imageEditor: HollowIdeFileEditor,
    textEditor: HollowIdeFileEditor,
    jsonModelEditor: HollowIdeFileEditor,
    audioEditor: HollowIdeFileEditor,
) {
    register(
        virtualFileType(
            id = AssetAudioFileTypeId,
            loader = { _, _ -> HollowIdeReadOnlyDocument },
            editor = audioEditor,
        ),
    )
    register(
        virtualFileType(
            id = AssetImageFileTypeId,
            loader = { path, bytes -> HollowIdeImageDocument(path, bytes, readOnly = true) },
            editor = imageEditor,
        ),
    )
    register(
        virtualFileType(
            id = AssetTextFileTypeId,
            loader = { _, bytes -> HollowIdeTextDocument(bytes.toString(Charsets.UTF_8), readOnly = true) },
            editor = textEditor,
        ),
    )
    register(
        virtualFileType(
            id = AssetJsonModelFileTypeId,
            loader = { _, _ -> HollowIdeReadOnlyDocument },
            editor = jsonModelEditor,
        ),
    )
}

private fun virtualFileType(
    id: String,
    loader: (String, ByteArray) -> HollowIdeFileDocument,
    editor: HollowIdeFileEditor,
) = HollowIdeFileType(
    id = id,
    matcher = { _, _ -> false },
    pathMatcher = { false },
    loader = loader,
    editor = editor,
)

private fun String.isVanillaJsonModel(): Boolean =
    startsWith("models/") && endsWith(".json", ignoreCase = true) && !endsWith(".geo.json", ignoreCase = true)

private fun String.isAudio(): Boolean = AudioFileExtensions.any { endsWith(it, ignoreCase = true) }

private fun String.isImage(): Boolean =
    endsWith(".png", ignoreCase = true) ||
            endsWith(".jpg", ignoreCase = true) ||
            endsWith(".jpeg", ignoreCase = true)
