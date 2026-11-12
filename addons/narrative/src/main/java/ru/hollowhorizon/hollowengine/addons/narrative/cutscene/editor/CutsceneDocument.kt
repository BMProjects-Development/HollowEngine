package ru.hollowhorizon.hollowengine.addons.narrative.cutscene.editor

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneData
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneEditorSession
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneStorage
import ru.hollowhorizon.hollowengine.client.history.UndoOwner
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileDocument

internal class CutsceneDocument(
    private val path: String,
    bytes: ByteArray,
    private val initialData: (String) -> CutsceneData = { CutsceneData(name = it) },
) : HollowIdeFileDocument, UndoOwner {
    val session = CutsceneEditorSession()
    override val history get() = session.timeline.history

    var error by mutableStateOf<String?>(null)
        private set

    private var name = path.substringAfterLast('/').substringBeforeLast('.')
    private var original by mutableStateOf<ByteArray?>(null)
    private var savedData by mutableStateOf(session.playback.toData(name))

    override val readOnly: Boolean get() = error != null
    override val isModified: Boolean by derivedStateOf {
        session.timeline.keysRevision
        !readOnly && (original?.isEmpty() == true || session.playback.toData(name) != savedData)
    }

    init {
        reload(bytes)
    }

    override fun encode(): ByteArray =
        if (readOnly) requireNotNull(original) else CutsceneStorage.encode(session.playback.toData(name))

    override fun reload(bytes: ByteArray) {
        if (original?.contentEquals(bytes) == true) return
        original = bytes
        try {
            val data = if (bytes.isEmpty()) initialData(path.substringAfterLast('/').substringBeforeLast('.'))
            else CutsceneStorage.decode(bytes, path)
            name = data.name
            session.load(data)
            savedData = session.playback.toData(name)
            error = null
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            HollowEngine.LOGGER.warn("Could not load cutscene {}", path, e)
        }
    }

    override fun markSaved() {
        savedData = session.playback.toData(name)
        original = CutsceneStorage.encode(savedData)
    }
}
