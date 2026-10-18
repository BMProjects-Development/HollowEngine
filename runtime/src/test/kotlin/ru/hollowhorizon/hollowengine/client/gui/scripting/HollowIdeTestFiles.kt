package ru.hollowhorizon.hollowengine.client.gui.scripting

import ru.hollowhorizon.hollowengine.client.ui.ide.*

internal val TestTextFileType = HollowIdeFileType.fallback(
    id = BuiltinTextFileTypeId,
    matcher = { _, bytes -> bytes.isProbablyText() },
    loader = { _, bytes -> HollowIdeTextDocument(bytes.toString(Charsets.UTF_8)) },
    editor = {},
)

internal fun testFileTypes() = HollowIdeFileTypeRegistry().apply {
    register(TestTextFileType)
}

internal fun testTextFile(path: String, text: String, readOnly: Boolean = false) =
    HollowIdeOpenFile(path, TestTextFileType, HollowIdeTextDocument(text, readOnly))
