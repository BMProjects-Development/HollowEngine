package ru.hollowhorizon.hollowengine.addons.narrative.cutscene.editor

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneData
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneEditorSession
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneStorage
import ru.hollowhorizon.hollowengine.client.history.UndoOwner
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileSnapshot

internal class CutsceneDocument(
    private val path: String,
    bytes: ByteArray,
    private val initialData: (String) -> CutsceneData = { CutsceneData(name = it) },
) : HollowIdeFileDocument, UndoOwner {
    val session = CutsceneEditorSession()
    override val history get() = session.timeline.history

    var error by mutableStateOf<String?>(null)
        private set

    private var name by mutableStateOf(path.substringAfterLast('/').substringBeforeLast('.'))
    private var original by mutableStateOf<ByteArray?>(null)
    private var savedData by mutableStateOf(session.playback.toData(name))
    private val currentData by derivedStateOf(structuralEqualityPolicy()) {
        session.timeline.keysRevision
        session.playback.toData(name)
    }

    override val readOnly: Boolean get() = error != null
    override val isModified: Boolean by derivedStateOf {
        !readOnly && (original?.isEmpty() == true || currentData != savedData)
    }

    init {
        reload(bytes)
    }

    override fun encode(): ByteArray =
        if (readOnly) requireNotNull(original) else CutsceneStorage.encode(currentData)

    override fun autosaveSnapshot(): HollowIdeFileSnapshot? = if (readOnly) null else SavedCutscene(currentData)

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
        val snapshot = SavedCutscene(currentData)
        markSaved(snapshot, snapshot.encode())
    }

    override fun markSaved(snapshot: HollowIdeFileSnapshot, bytes: ByteArray) {
        savedData = (snapshot as SavedCutscene).data
        original = bytes
    }

    private data class SavedCutscene(val data: CutsceneData) : HollowIdeFileSnapshot {
        override fun encode(): ByteArray = CutsceneStorage.encode(data)
    }
}
