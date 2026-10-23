import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.bbmodel.*
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.applyAnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.modelMatrix
import ru.hollowhorizon.hollowengine.client.models.internal.v2.modelRotation
import ru.hollowhorizon.hollowengine.client.models.internal.v2.walk
import ru.hollowhorizon.hollowengine.common.models.IkSolver
import ru.hollowhorizon.hollowengine.common.models.LayerBlendMode
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BlockBenchRigTests {
    private fun node(id: String, vararg children: Any): JsonObject = buildJsonObject {
        put("uuid", id)
        put("children", JsonArray(children.map { if (it is String) JsonPrimitive(it) else it as JsonObject }))
    }

    private fun convert(file: BlockBenchModel): Model =
        BlockBenchModelLoader.convert(file, "test:models/rig.bbmodel".rl, ModelSide.SERVER)

    private fun skeleton(model: Model): Map<Int, RuntimeNode> =
        model.scenes[0].nodes.map { RuntimeNode(it, null) }.onEach(RuntimeNode::updateHierarchyMatrices)
            .flatMap { it.walk() }.associateBy { it.definition.index }

    private fun triangle(id: String) = BBElement(
        id, id, "mesh", origin = listOf(2f, 0f, 0f),
        vertices = mapOf("a" to listOf(0f, 0f, 0f), "b" to listOf(1f, 0f, 0f), "c" to listOf(0f, 1f, 0f)),
        faces = mapOf(
            "face" to BBFace(
                uv = buildJsonObject {
                    put("a", JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(0))))
                    put("b", JsonArray(listOf(JsonPrimitive(16), JsonPrimitive(0))))
                    put("c", JsonArray(listOf(JsonPrimitive(0), JsonPrimitive(16))))
                },
                vertices = listOf("a", "b", "c"),
            )
        ),
    )

    @Test
    fun `armature origins are local and the rest skin leaves every vertex in place`() {
        val file = BlockBenchModel(
            meta = BBMeta(version = "5.0"), elements = listOf(
                BBElement("rig", "rig", "armature"),
                BBElement(
                    "hip",
                    "hip",
                    "armature_bone",
                    origin = listOf(0f, 8f, 0f),
                    rotation = listOf(0f, 0f, 25f),
                    vertexWeights = mapOf("mesh01:a" to 1f, "mesh01:b" to 0.5f)
                ),
                BBElement(
                    "tip",
                    "tip",
                    "armature_bone",
                    origin = listOf(0f, 8f, 0f),
                    vertexWeights = mapOf("mesh01:b" to 0.5f)
                ),
                triangle("mesh01-uuid"),
            ), nodes = listOf(node("rig", node("hip", "tip"), "mesh01-uuid"))
        )
        val model = convert(file)
        val runtime = skeleton(model)
        val mesh = model.findNodeByName("mesh01-uuid")!!
        val skin = assertNotNull(mesh.skin)
        assertEquals(8f, model.findNodeByName("tip")!!.transform.translation.y)
        val matrices = skin.compute(runtime.getValue(mesh.index).modelMatrix(), runtime)
        val primitive = mesh.mesh!!.primitives.single()
        assertTrue(primitive.hasSkinning && primitive.isDynamic)
        primitive.positions!!.forEach { point ->
            matrices.forEach { matrix ->
                val result = matrix.transpose(MutableMat4f()).transform(point, 1f, MutableVec3f())
                assertEquals(point.x, result.x, 0.0001f)
                assertEquals(point.y, result.y, 0.0001f)
                assertEquals(point.z, result.z, 0.0001f)
            }
        }
        assertEquals(2, primitive.joints!![2].x)
        assertEquals(1f, primitive.jointWeights!![2].x)
    }

    @Test
    fun `locators and IK controllers under armature bones keep local positions`() {
        val model = convert(
            BlockBenchModel(
                elements = listOf(
                    BBElement("rig", "rig", "armature"),
                    BBElement("bone", "bone", "armature_bone", origin = listOf(0f, 8f, 0f)),
                    BBElement("locator", "locator", "locator", position = listOf(1f, 2f, 3f)),
                    BBElement("control", "control", "null_object", position = listOf(4f, 5f, 6f)),
                ), nodes = listOf(node("rig", node("bone", "locator", "control")))
            )
        )
        assertEquals(Vec3f(1f, 2f, 3f), model.findNodeByName("locator")!!.transform.translation)
        assertEquals(Vec3f(4f, 5f, 6f), model.findNodeByName("control")!!.transform.translation)
    }

    @Test
    fun `more than four painted influences survive normalized without truncation`() {
        val bones = (0..5).map { index ->
            BBElement("bone$index", "bone$index", "armature_bone", vertexWeights = mapOf("mesh01:a" to (index + 1f)))
        }
        val file =
            BlockBenchModel(
                elements = listOf(BBElement("rig", type = "armature"), triangle("mesh01-uuid")) + bones,
                nodes = listOf(node("rig", *(bones.map { it.uuid } + "mesh01-uuid").toTypedArray())))
        val primitive = convert(file).findNodeByName("mesh01-uuid")!!.mesh!!.primitives.single()
        val primary = primitive.jointWeights!![0]
        val extra = assertNotNull(primitive.additionalSkinWeights)[0]
        assertEquals(listOf(4, 5), extra.map { it.joint })
        assertEquals(
            1f,
            primary.x + primary.y + primary.z + primary.w + extra.sumOf { it.weight.toDouble() }.toFloat(),
            0.00001f
        )
        assertEquals(6f / 21f, extra[1].weight, 0.00001f)
    }

    @Test
    fun `an IK target at the chain root folds equal links instead of leaving the chain straight`() {
        val solution = IkSolver.solve(
            listOf(Vec3f.ZERO, Vec3f(0f, 1f, 0f), Vec3f(0f, 2f, 0f)),
            Vec3f.ZERO, Vec3f(0f, 0f, 1f), Vec3f.X_AXIS, maxStretch = 1f,
        )
        assertTrue(solution.joints.last().length() < 0.001f)
        assertEquals(1f, solution.joints[0].distance(solution.joints[1]), 0.0001f)
        assertEquals(1f, solution.joints[1].distance(solution.joints[2]), 0.0001f)
    }

    @Test
    fun `IK uses UUID references with duplicate names poles and a locked end rotation`() {
        val model = convert(
            BlockBenchModel(
                meta = BBMeta(version = "5.0"), elements = listOf(
                    BBElement("root", "bone"),
                    BBElement("mid", "bone", origin = listOf(0f, 8f, 0f)),
                    BBElement("end", "bone", origin = listOf(0f, 16f, 0f)),
                    BBElement("pole", "pole", "locator", position = listOf(8f, 0f, 8f)),
                    BBElement(
                        "control",
                        "control",
                        "null_object",
                        position = listOf(8f, 8f, 0f),
                        ikTarget = "end",
                        ikSource = "root",
                        ikPole = "pole",
                        lockIkTargetRotation = true
                    ),
                ), nodes = listOf(node("root", node("mid", "end")), JsonPrimitive("control"), JsonPrimitive("pole"))
            )
        )
        val runtime = skeleton(model)
        val root = runtime.values.single { it.definition.index == 0 }
        val mid = root.children.single()
        val end = mid.children.single()
        val control = runtime.values.single { it.name == "control" }
        model.constraints.single().apply(runtime)
        assertEquals(1f, end.modelMatrix().getTranslation().y, 0.0001f) // Inactive without controller keys.
        control.isAnimated = true
        model.constraints.single().apply(runtime)
        assertTrue(end.modelMatrix().getTranslation().distance(control.modelMatrix().getTranslation()) < 0.001f)
        assertTrue(mid.modelMatrix().getTranslation().z > 0f)
        val rotation = end.modelRotation()
        assertEquals(1f, rotation.w, 0.0001f)
    }

    @Test
    fun `global rotations resolve the animated parent before masking and blending`() {
        fun rotation(x: Int, y: Int, z: Int) = BBKeyframe("rotation", 0f, dataPoints = listOf(buildJsonObject {
            put("x", x); put("y", y); put("z", z)
        }))

        val model = convert(
            BlockBenchModel(
                meta = BBMeta(version = "5.0"),
                elements = listOf(BBElement("parent", "parent"), BBElement("child", "child")),
                nodes = listOf(node("parent", "child")),
                animations = listOf(
                    BBAnimation(
                        "turn", animators = mapOf(
                            "parent" to BBAnimator(keyframes = listOf(rotation(0, 90, 0))),
                            "child" to BBAnimator(rotationGlobal = true, keyframes = listOf(rotation(0, 0, 0))),
                        )
                    )
                )
            )
        )
        val runtime = skeleton(model)
        val pose = AnimationPose.sample(model.animations.single(), 0f)
        applyAnimationPose(pose, runtime, LayerBlendMode.Override, 1f)
        val child = runtime.values.single { it.name == "child" }
        assertEquals(1f, child.modelRotation().w, 0.0001f)
        val masked = AnimationPose.sample(model.animations.single(), 0f, setOf(child.definition.index))
        assertEquals(1, masked.entries.size)
        assertEquals(pose[child.definition.index]!!.rotation, masked[child.definition.index]!!.rotation)
    }
}
