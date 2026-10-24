import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeMontageDocument
import ru.hollowhorizon.hollowengine.client.ui.ide.files.montage.MontageRigEditing
import ru.hollowhorizon.hollowengine.client.ui.ide.files.montage.MontageTimelineSession
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigPose
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.eulerDegreesXyz
import ru.hollowhorizon.hollowengine.common.utils.math.eulerRotationXyz
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class MontageRigEditingTest {
    @Test
    fun `picking a bone updates track highlights without filtering the track list`() {
        val (_, session) = session(recording = false)
        session.select("Body", emptyList())
        val body = session.timeline.groups.single { it.isSelected }
        session.select("Arm", emptyList())
        val arm = session.timeline.groups.single { it.isSelected }
        assertTrue(body !== arm)
        assertTrue(body.isListed)
        assertTrue(!body.isSelected)
        session.select(null, emptyList())
        assertEquals(0, session.timeline.groups.count { it.isSelected })
    }

    private fun session(recording: Boolean): Pair<MontageRigEditing, MontageTimelineSession> {
        val document = HollowIdeMontageDocument("{\"duration\": 2.0}".toByteArray())
        val rigs = MontageRigEditing(document) { ModelRig() }
        val session = MontageTimelineSession(document, rigs)
        session.sync()
        session.timeline.isRecording = recording
        return rigs to session
    }

    private fun MontageRigEditing.pose(bone: String, pose: RigPose?) =
        edit { rig -> rig.withBone(bone, rig.holder(bone).copy(pose = pose)) }

    @Test
    fun `an edit that takes a bone back to rest reaches the montage, though the bone drops out of the rig`() {
        listOf(false, true).forEach { recording ->
            val (rigs, session) = session(recording)
            rigs.pose("Body", RigPose(rotation = eulerRotationXyz(Vec3f(30f, 0f, 0f))))
            assertTrue(!session.bonePose("Body").rotation.isFuzzyEqualRotation(QuatF.IDENTITY, 1e-3f))

            rigs.pose("Body", null)
            val turned = session.bonePose("Body").rotation.eulerDegreesXyz()
            assertTrue(turned.isFuzzyEqual(Vec3f.ZERO, 1e-3f), "recording=$recording left the bone turned by $turned")
        }
    }
}
