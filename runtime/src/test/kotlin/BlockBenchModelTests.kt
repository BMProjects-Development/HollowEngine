import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.bbmodel.*
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlockBenchModelTests {
    private fun convert(file: BlockBenchModel): Model =
        BlockBenchModelLoader.convert(file, "test:models/example.bbmodel".rl, ModelSide.SERVER)

    private fun key(
        time: Float,
        x: Number,
        y: Number = 0,
        z: Number = 0,
        interpolation: String = "linear",
        channel: String = "position",
    ) = BBKeyframe(channel, time, interpolation, listOf(buildJsonObject {
        put("x", x.toString()); put("y", y.toString()); put("z", z.toString())
    }))

    @Test
    fun `the bundled v5 model loads all geometry and clips without a render context`() {
        val source = javaClass.classLoader.getResourceAsStream("assets/hollowengine/models/entity/hv_malora.bbmodel")!!
            .bufferedReader().use { it.readText() }
        val file = BlockBenchModel.parse(source)
        val model = convert(file)
        assertEquals(13, model.animations.size)
        assertEquals(154, model.nodes.count { it.mesh != null })
        assertEquals(1, model.nodes.count { !it.visible })
        assertEquals(4, file.textures.size)
        assertTrue(model.nodes.any { it.name == "aura_vfx" })
        assertEquals(16, file.textures[1].height / file.textures[1].width)
        model.animations.forEach { clip ->
            assertTrue(clip.duration > 0f, clip.name)
            for (time in listOf(0f, clip.duration * 0.37f, clip.duration)) {
                val pose = AnimationPose.sample(clip, time)
                pose.entries.forEach { (_, bone) ->
                    bone.rotation?.let { assertTrue(it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.w.isFinite()) }
                    bone.translation?.let { assertTrue(it.x.isFinite() && it.y.isFinite() && it.z.isFinite()) }
                }
            }
        }
    }

    @Test
    fun `legacy inline groups and v5 group table preserve pivots and rotations`() {
        val inline = """{"meta":{"format_version":"4.10"},"outliner":[
            {"uuid":"root","name":"body","origin":[0,16,0],"rotation":[0,90,0],"children":[
            {"uuid":"child","name":"head","origin":[0,24,0],"children":[]}]}]}"""
        val modern = """{"meta":{"format_version":"5.0"},"groups":[
            {"uuid":"root","name":"body","origin":[0,16,0],"rotation":[0,90,0]},
            {"uuid":"child","name":"head","origin":[0,24,0]}],
            "outliner":[{"uuid":"root","children":[{"uuid":"child","children":[]}]}]}"""
        val old = convert(BlockBenchModel.parse(inline))
        val current = convert(BlockBenchModel.parse(modern))
        assertEquals(Vec3f(0f, 8f, 0f), current.findNodeByName("head")!!.transform.translation)
        assertEquals(old.nodes.map { it.name }, current.nodes.map { it.name })
        val root = current.scenes[0].nodes.single()
        val world = MutableMat4f(root.localMatrix).mul(root.children.single().localMatrix)
            .mul(current.findNodeByName("head")!!.localMatrix).getTranslation()
        assertEquals(1.5f, world.y, 0.0001f)
    }

    @Test
    fun `legacy animation axes are converted but v5 keeps its authored signs`() {
        fun model(version: String) = convert(
            BlockBenchModel(
                meta = BBMeta(version = version),
                elements = listOf(BBElement("bone", "bone")),
                animations = listOf(
                    BBAnimation(
                        "move",
                        1f,
                        animators = mapOf("bone" to BBAnimator(keyframes = listOf(key(0f, 4, 5, 6))))
                    )
                ),
            )
        )

        val old = model("4.10").animations.single().nodes.values.single().translation!!.compute(0f)
        val modern = model("5.0").animations.single().nodes.values.single().translation!!.compute(0f)
        assertEquals(Vec3f(-4f, 5f, 6f), old)
        assertEquals(Vec3f(4f, 5f, 6f), modern)
    }

    @Test
    fun `Euler animation retains complete spins and adds to the authored Euler rest pose`() {
        val file = BlockBenchModel(
            meta = BBMeta(version = "5.0"),
            elements = listOf(BBElement("bone", "bone", rotation = listOf(25f, 40f, 10f))),
            animations = listOf(
                BBAnimation(
                    "spin", 1f, animators = mapOf(
                        "bone" to BBAnimator(
                            keyframes = listOf(
                                key(0f, 0, channel = "rotation"), key(1f, 0, 360, channel = "rotation"),
                            )
                        )
                    )
                )
            ),
        )
        val model = convert(file)
        val node = model.findNodeByName("bone")!!
        val offset = model.animations.single().nodes.getValue(node.index).rotation!!.compute(0.5f)
        val actual = node.transform.rotation * offset
        val expected = bbRotation(Vec3f(25f, 220f, 10f))
        assertEquals(1f, absDot(actual, expected), 0.0001f)
        assertTrue(absDot(offset, QuatF.IDENTITY) < 0.1f)
    }

    @Test
    fun `step keys hold until the next key and pre-post discontinuities are retained`() {
        val points = listOf(buildJsonObject { put("x", 2) }, buildJsonObject { put("x", 6) })
        val file = BlockBenchModel(
            elements = listOf(BBElement("bone")), meta = BBMeta(version = "5.0"), animations = listOf(
                BBAnimation(
                    "step", animators = mapOf(
                        "bone" to BBAnimator(
                            keyframes = listOf(
                                BBKeyframe("position", 0f, "step", points), key(1f, 10),
                            )
                        )
                    )
                )
            )
        )
        val track = convert(file).animations.single().nodes.values.single().translation!!
        assertEquals(2f, track.compute(0f).x)
        assertEquals(6f, track.compute(0.5f).x)
        assertEquals(10f, track.compute(1f).x)
    }

    @Test
    fun `curved tracks follow Catmull-Rom endpoints Bezier time handles and expression time`() {
        val catmull = BBVectorTrack(listOf(key(0f, 0, interpolation = "catmullrom"), key(1f, 8)), false, false)
        assertEquals(1.625f, catmull.compute(0.25f).x, 0.0001f)
        val bezier = BBVectorTrack(
            listOf(
                key(0f, 0, interpolation = "bezier").copy(bezierRightTime = listOf(1f / 3, 1f / 3, 1f / 3)),
                key(1f, 8, interpolation = "bezier").copy(bezierLeftTime = listOf(-1f / 3, -1f / 3, -1f / 3)),
            ), false, false
        )
        assertEquals(1.25f, bezier.compute(0.25f).x, 0.0001f)
        val expression = BBVectorTrack(listOf(BBKeyframe("position", 0f, dataPoints = listOf(buildJsonObject {
            put("x", "q.anim_time * 4")
        }))), false, false)
        assertEquals(8f, expression.compute(2f).x)
    }

    @Test
    fun `flipbook order supports backwards ping-pong custom holds and interpolation`() {
        val reverse = BBTextureTimeline(4, 2f, "backwards")
        assertEquals(listOf(3, 2, 1, 0, 3), (0..4).map { reverse.sample(it / 2.0).frame })
        val pingPong = BBTextureTimeline(4, 1f, "back_and_forth")
        assertEquals(listOf(0, 1, 2, 3, 2, 1, 0), (0..6).map { pingPong.sample(it.toDouble()).frame })
        val custom = BBTextureTimeline(4, 1f, "custom", "0 2 2 1")
        assertEquals(2, custom.sample(2.25).frame)
        assertEquals(1, custom.sample(2.25).next)
        assertEquals(0.25f, custom.sample(2.25).fraction)
        assertEquals(0, BBTextureTimeline(1, 7f, "back_and_forth").sample(10.0).frame)
        assertFailsWith<IllegalArgumentException> { BBTextureTimeline(2, 7f, "custom", "0 2") }
    }

    @Test
    fun `null faces are omitted while untextured faces and UV rotation are retained`() {
        val model = convert(
            BlockBenchModel(
                elements = listOf(
                    BBElement(
                        "cube",
                        "cube",
                        type = "cube",
                        from = listOf(0f, 0f, 0f),
                        to = listOf(16f, 16f, 16f),
                        faces = mapOf(
                            "north" to BBFace(
                                JsonArray(listOf(0, 0, 16, 16).map(::JsonPrimitive)),
                                JsonPrimitive(false),
                                90
                            ),
                            "south" to BBFace(texture = null),
                        )
                    )
                )
            )
        )
        val primitive = model.findNodeByName("cube")!!.mesh!!.primitives.single()
        assertEquals(4, primitive.positions!!.size)
        assertEquals(6, primitive.indices!!.size)
        assertEquals(-1f, primitive.normals!![0].z, 0.0001f)
        assertEquals(1f, primitive.texCoords!![0].x)
        assertEquals(1f, primitive.texCoords!![0].y)
    }

    @Test
    fun `malformed outliners and missing texture references fail with useful errors`() {
        assertFailsWith<IllegalArgumentException> {
            convert(
                BlockBenchModel(
                    elements = listOf(BBElement("x")),
                    nodes = listOf(JsonPrimitive("x"), JsonPrimitive("x"))
                )
            )
        }
        assertFailsWith<IllegalStateException> {
            convert(BlockBenchModel(nodes = listOf(JsonPrimitive("missing"))))
        }
    }

    private fun absDot(a: QuatF, b: QuatF) = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w)
}
