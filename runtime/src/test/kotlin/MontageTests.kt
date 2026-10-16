import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockContext
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationData
import ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations.Interpolator
import ru.hollowhorizon.hollowengine.client.models.internal.animator.*
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.sounds.LowPassFilter
import ru.hollowhorizon.hollowengine.common.sounds.SoundBoneAttachmentSpec
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.VfxInterpolation
import ru.hollowhorizon.hollowengine.common.vfx.VfxKey
import ru.hollowhorizon.hollowengine.common.vfx.VfxTrackCurve
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MontageTests {
    private val arm = RuntimeNode(
        NodeDefinition(index = 1, name = "arm", children = mutableListOf(), transform = TrsTransformF()), parent = null
    )
    private val leg = RuntimeNode(
        NodeDefinition(index = 0, name = "leg", children = mutableListOf(), transform = TrsTransformF()), parent = null
    )
    private val target = PoseTarget(
        mapOf(0 to leg, 1 to arm),
        mapOf("walk" to clip("walk", 1f, 1f), "wave" to clip("wave", 1f, 5f)),
    )

    @Test
    fun `clips stack like layers, each fading over what lies beneath it, and a masked one leaves the other bones alone`() {
        val montage = Montage(
            duration = 2f,
            clips = listOf(
                MontageClip("a", "walk", start = 0f, length = 1.5f, fadeIn = 0.2f, fadeOut = 0.5f, loop = true),
                MontageClip("b", "wave", start = 1f, length = 1f, fadeIn = 0.5f, mask = BoneMask.of("arm")),
            ),
        )

        val pose = MontageSampler.sample(montage, 1.25f, target, setOf(0, 1))

        assertEquals(0.25f, pose[0]!!.translation!!.x, 1e-4f)
        assertEquals(0.5f, pose[0]!!.cover, 1e-4f)
        assertEquals(0.25f / 3f + 1.25f * 2f / 3f, pose[1]!!.translation!!.x, 1e-4f)
        assertEquals(0.75f, pose[1]!!.cover, 1e-4f)
        assertNull(MontageSampler.sample(montage, 1.75f, target, setOf(0, 1))[0])
    }

    @Test
    fun `keyed bones move on from where the clips leave them, and a clip's weight curve scales its share`() {
        val montage = Montage(
            duration = 1f,
            clips = listOf(
                MontageClip(
                    "a", "walk", length = 1f, weight = listOf(VfxKey(0f, 0.5f, VfxInterpolation.LINEAR))
                )
            ),
            bones = listOf(
                MontageBoneTrack(
                    "leg",
                    MontageBoneProperty.POSITION,
                    listOf(VfxTrackCurve(0, keys = listOf(VfxKey(0f, 2f, VfxInterpolation.LINEAR))))
                ),
            ),
        )

        val pose = MontageSampler.sample(montage, 0.25f, target, setOf(0, 1))

        assertEquals(0.25f + 2f, pose[0]!!.translation!!.x, 1e-4f)
        assertEquals(0.5f, pose[0]!!.cover, 1e-4f)
    }

    @Test
    fun `the first clip fading in blends from the pose the montage is played over`() {
        val montage = Montage(duration = 1f, clips = listOf(MontageClip("a", "walk", length = 1f, fadeIn = 0.2f)))
        leg.transform.translation.set(Vec3f(10f, 0f, 0f))

        applyAnimationPose(
            MontageSampler.sample(montage, 0.1f, target, setOf(0)), mapOf(0 to leg), LayerBlendMode.Override, 1f
        )

        assertEquals((10f + 0.1f) / 2f, leg.transform.translation.x, 1e-4f)
    }

    @Test
    fun `a montage layer holds its last moment while it fades out after playing through, then ends`() {
        val id = "test:montages/once"
        MontageAssets.put(id, Montage(duration = 1f, clips = listOf(MontageClip("walk", "walk", length = 1f))))
        val layer = MontageLayer(MontageLayerSpec(montage = id, fadeIn = 0f, fadeOut = 0.5f))
        val context = AnimatorEvaluationContext()

        context.deltaTime = 1.25f
        layer.weight(context)
        val fading = layer.sample(target, context)!!
        assertEquals(0.5f, fading.weightScale, 1e-4f)
        assertEquals(1f, fading.pose[0]!!.translation!!.x, 1e-4f)

        context.deltaTime = 0.5f
        layer.weight(context)
        assertNull(layer.sample(target, context))
        assertTrue(layer.finished)
    }

    @Test
    fun `a montage hangs its attachments for their stretch of time and animates fields over what is authored`() {
        val rig = ModelRig(
            bones = mapOf(
                "hand" to RigBone(
                    attachments = listOf(
                        IkChainSpec(
                            id = "reach", weight = AnimationExpression("0.2")
                        )
                    )
                )
            )
        )
        val montage = MontageFormat.read(
            """
            {
              "duration": 2,
              "attachments": [ { "bone": "hand", "spec": { "type": "hollowengine:rig/ik_target", "id": "cup" }, "windows": [ { "start": 0.5, "end": 1.5 } ] } ],
              "tracks": [
                { "bone": "hand", "attachment": "reach", "property": "weight",
                  "curves": [ { "keys": [ { "time": 0, "value": 1, "interpolation": "LINEAR" } ] } ] },
                { "bone": "hand", "attachment": "cup", "property": "offset",
                  "curves": [ { "channel": 1, "keys": [ { "time": 0, "value": 0, "interpolation": "LINEAR" }, { "time": 2, "value": 2, "interpolation": "LINEAR" } ] } ] }
              ]
            }
            """
        )

        val before = rig.withMontages(listOf(MontageMoment(montage, 0.25f, 0.5f)))
        assertNull(before.rig.bone("hand")!!.attachment("cup"))
        // The model's own chain eases toward the track by how much the montage counts: half way from 0.2 to 1.
        assertEquals("0.6", (before.rig.bone("hand")!!.attachment("reach") as IkChainSpec).weight.source)

        val during = rig.withMontages(listOf(MontageMoment(montage, 1f, 0.5f)))
        // What the montage brought takes its track as it is, whatever the montage's weight.
        assertEquals(Vec3f(0f, 1f, 0f), (during.rig.bone("hand")!!.attachment("cup") as IkTargetSpec).offset)
        assertTrue(during.key != before.key)
    }

    @Test
    fun `a sound keeps time with its stretch, is held between them, and animates fields nested in its playback`() {
        val montage = MontageFormat.read(
            """
            {
              "duration": 4,
              "attachments": [ { "bone": "hand", "windows": [ { "start": 1, "end": 2 } ], "spec": {
                "type": "hollowengine:rig/sound", "id": "hum", "source": "minecraft:block.beacon.ambient",
                "playback": { "filters": [ { "type": "hollowengine:sound/low_pass", "cutoff": 1000 } ] } } } ],
              "tracks": [
                { "bone": "hand", "attachment": "hum", "property": "playback.volume",
                  "curves": [ { "keys": [ { "time": 0, "value": 0.25, "interpolation": "LINEAR" } ] } ] },
                { "bone": "hand", "attachment": "hum", "property": "playback.filters.0.cutoff",
                  "curves": [ { "keys": [ { "time": 0, "value": 400, "interpolation": "LINEAR" } ] } ] }
              ]
            }
            """
        )

        fun sound(time: Float) =
            ModelRig.EMPTY.withMontages(listOf(MontageMoment(montage, time, 1f))).rig.bone("hand")!!
                .attachment("hum") as SoundBoneAttachmentSpec

        val playing = sound(1.5f)
        assertEquals(0.5f, playing.clock!!, 1e-5f)
        assertEquals(0.25f, playing.playback.volume)
        assertEquals(400f, (playing.playback.filters.single() as LowPassFilter).cutoff)

        val held = sound(3f)
        assertEquals(false, held.playing)
        assertNull(held.clock)

        val fields = RigFields.animatable(playing).map { it.name }
        assertTrue("playback.volume" in fields && "playback.filters.0.cutoff" in fields, "$fields")
        assertTrue(
            fields.none { it.startsWith("playback.repeat") || it == "playback.fadeIn" || it == "clock" },
            "$fields"
        )
        assertEquals(
            listOf("playback.pitch"),
            RigFields.changed(playing, playing.copy(playback = playing.playback.copy(pitch = 2f)))
        )
    }

    /** A clip that moves a bone along x by [rate] times the time it is sampled at. */
    private fun clip(name: String, duration: Float, rate: Float) = AnimationClip(
        name = name,
        nodes = listOf(0, 1).associateWith {
            AnimationData(
                translation = Rate(duration, rate), rotation = null, scale = null, weights = null
            )
        },
        duration = duration,
    )

    private class Rate(override val duration: Float, private val rate: Float) : Interpolator<Vec3f> {
        override fun compute(time: Float, context: BedrockContext): Vec3f = Vec3f(time * rate, 0f, 0f)
    }
}
