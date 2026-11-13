package ru.hollowhorizon.hollowengine.addons.narrative.cutscene

import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.editor.CutsceneDocument
import ru.hollowhorizon.hollowengine.client.camera.CameraPose
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CutsceneDocumentTest {
    private val path = "cutscenes/intro.dat"
    private val origin = CutsceneOrigin(10f, 60f, -20f, 90f)
    private val pose = CameraPose(Vec3f(12f, 63f, -20f), Vec3f(10f, 90f, 5f), 48f)

    @Test
    fun `a camera key saved in the editor loads in the playback controller`() {
        val document = CutsceneDocument(path, CutsceneStorage.encode(CutsceneData(name = "Intro", origin = origin)))
        document.session.captureCamera(2f, pose)
        val saved = CutsceneStorage.decode(document.encode(), path)
        val playback = CutscenePlaybackController()
        playback.setupTracks(saved)
        playback.seek(2f)

        assertEquals("Intro", saved.name)
        assertEquals(origin, saved.origin)
        assertEquals(pose.position.x, playback.currentPose.position.x, 0.0001f)
        assertEquals(pose.position.y, playback.currentPose.position.y, 0.0001f)
        assertEquals(pose.position.z, playback.currentPose.position.z, 0.0001f)
        assertEquals(pose.rotation, playback.currentPose.rotation)
        assertEquals(pose.fov, playback.currentPose.fov)
    }

    @Test
    fun `camera navigation is temporary until a key is inserted`() {
        val bytes = CutsceneStorage.encode(CutsceneData(origin = origin))
        val document = CutsceneDocument(path, bytes)
        val otherDocument = CutsceneDocument(path, bytes)
        val session = document.session
        session.cameraDraft.value = pose
        assertFalse(document.isModified)
        assertEquals(pose, session.previewPose)

        session.captureCamera(2f, session.previewPose)
        assertTrue(document.isModified)
        assertFalse(otherDocument.isModified)
        assertEquals(0f, otherDocument.session.timeline.currentTime)
        assertTrue(document.history.undo())
        assertFalse(document.isModified)
        assertTrue(document.history.redo())
        document.markSaved()
        assertFalse(document.isModified)
    }

    @Test
    fun `a damaged file is preserved as read only`() {
        val bytes = byteArrayOf(0)
        val document = CutsceneDocument(path, bytes)

        assertTrue(document.readOnly)
        assertTrue(bytes.contentEquals(document.encode()))
    }

    @Test
    fun `acknowledging an autosave leaves a newer camera edit dirty`() {
        val document = CutsceneDocument(path, CutsceneStorage.encode(CutsceneData(origin = origin)))
        document.session.captureCamera(2f, pose)
        val snapshot = assertNotNull(document.autosaveSnapshot())

        document.session.captureCamera(3f, pose)
        val saved = snapshot.encode()
        document.markSaved(snapshot, saved)

        assertTrue(document.isModified)
        assertNotEquals(CutsceneStorage.decode(document.encode(), path), CutsceneStorage.decode(saved, path))
        assertTrue(document.history.undo())
        assertFalse(document.isModified)
        assertEquals(snapshot, document.autosaveSnapshot())
    }

    @Test
    fun `the autosave snapshot observes origin and duration changes`() {
        val document = CutsceneDocument(path, CutsceneStorage.encode(CutsceneData(origin = origin)))
        val before = document.autosaveSnapshot()
        val newOrigin = origin.copy(x = 32f)

        document.session.playback.origin = newOrigin
        val afterOrigin = assertNotNull(document.autosaveSnapshot())
        assertNotEquals(before, afterOrigin)
        assertEquals(newOrigin, CutsceneStorage.decode(afterOrigin.encode(), path).origin)

        document.session.timeline.workAreaEnd = 18f
        val afterDuration = assertNotNull(document.autosaveSnapshot())
        assertNotEquals(afterOrigin, afterDuration)
        assertEquals(18f, CutsceneStorage.decode(afterDuration.encode(), path).duration)
    }

    @Test
    fun `a blank project file can be initialized and saved as a cutscene`() {
        val document = CutsceneDocument(path, byteArrayOf())

        assertFalse(document.readOnly)
        assertTrue(document.isModified)
        assertEquals("intro", CutsceneStorage.decode(document.encode(), path).name)
        document.markSaved()
        assertFalse(document.isModified)
    }

    @Test
    fun `inserting a lens key leaves position and rotation tracks untouched`() {
        val session = CutsceneEditorSession()
        session.captureCamera(3f, pose, channels = CameraKeyChannels.FOV)

        assertTrue(session.playback.translation.keyframes.isEmpty())
        assertTrue(session.playback.rotation.keyframes.isEmpty())
        assertEquals(48f, session.playback.fov.valueAt(3f))
    }
}
