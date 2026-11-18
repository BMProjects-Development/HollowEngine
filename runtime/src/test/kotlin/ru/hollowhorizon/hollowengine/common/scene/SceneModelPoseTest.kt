package ru.hollowhorizon.hollowengine.common.scene

import ru.hollowhorizon.hollowengine.client.models.renderModelNode
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Mesh
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.applyAnimationPose
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.models.LayerBlendMode
import ru.hollowhorizon.hollowengine.common.models.ModelNodeSource
import ru.hollowhorizon.hollowengine.common.models.modelMatrix
import ru.hollowhorizon.hollowengine.common.models.modelRotation
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.deg
import kotlin.math.abs
import kotlin.test.*

class SceneModelPoseTest {
    @Test
    fun `reset reuses an unchanged transform and follows a changed rest pose`() {
        val scene = Scene(object : SceneContext { override val side = LogicalSide.SERVER })
        val node = scene.create("bone")
        val rest = TrsTransformF().translate(1f, 0f, 0f)
        val definition = NodeDefinition(0, "bone", mutableListOf(), rest)
        val pose = SceneModelPose(node, definition)
        pose.resetPose()
        val version = node.localTransform.modCount
        pose.resetPose()
        assertEquals(version, node.localTransform.modCount)

        node.localTransform.translate(4f, 0f, 0f)
        pose.resetPose()
        assertEquals(Vec3f(1f, 0f, 0f), node.localTransform.translation)
        definition.baseTransform.translate(0f, 2f, 0f)
        pose.resetPose()
        assertEquals(Vec3f(1f, 2f, 0f), node.localTransform.translation)
        scene.close()
    }

    @Test
    fun `scene and existing runtime use the same pose blending`() {
        for (mode in LayerBlendMode.entries) {
            val scene = Scene(object : SceneContext {
                override val side = LogicalSide.SERVER
            })
            val node = scene.create("bone")
            val weights = floatArrayOf(0.25f, 0.75f)
            val definition = NodeDefinition(
                index = 7,
                name = "bone",
                children = mutableListOf(),
                transform = TrsTransformF().translate(1f, 0f, 0f),
                mesh = Mesh(listOf(Primitive(material = Material(), weights = weights)), weights),
            )
            val existing = renderModelNode(definition, null)
            node.localTransform.set(definition.baseTransform)
            val scenePose = SceneModelPose(node, definition, weights)
            val pose = AnimationPose().apply {
                bone(7).translation = Vec3f(2f, 3f, 0f)
                bone(7).scale = Vec3f(2f, 1f, 1f)
                bone(7).weights = floatArrayOf(1f, 0f)
            }
            val reference = AnimationPose().apply {
                bone(7).translation = Vec3f(1f, 0f, 0f)
                bone(7).weights = floatArrayOf(0.5f, 0.5f)
            }

            applyAnimationPose(pose, mapOf(7 to existing), mode, 0.5f, reference)
            applyAnimationPose(pose, mapOf(7 to scenePose), mode, 0.5f, reference)

            assertEquals(existing.transform.translation, node.localTransform.translation)
            assertEquals(existing.transform.scale, node.localTransform.scale)
            assertContentEquals(existing.morphWeights, scenePose.morphWeights)
            existing.close()
            scene.close()
        }
    }

    @Test
    fun `animation poses a scene object without changing another instance`() {
        val scene = Scene(object : SceneContext {
            override val side = LogicalSide.SERVER
        })
        val first = scene.create("first")
        val second = scene.create("second")
        val rest = TrsTransformF().translate(1f, 0f, 0f)
        first.localTransform.set(rest)
        second.localTransform.set(rest)
        val sourceWeights = floatArrayOf(0.25f, 0.75f)
        val source = NodeDefinition(7, "bone", mutableListOf(), rest)
        val firstPose = SceneModelPose(first, source, sourceWeights)
        val secondPose = SceneModelPose(second, source, sourceWeights)
        val aliasedSource = object : ModelNodeSource {
            override val index = 0
            override val name = "bone"
            override val path = "bone"
            override val hasMesh = false
            override val skinJoints = emptyList<Int>()
            override val restTransform = first.localTransform
        }
        assertFailsWith<IllegalArgumentException> { SceneModelPose(first, aliasedSource) }
        val animation = AnimationPose().apply {
            bone(7).translation = Vec3f(2f, 3f, 0f)
            bone(7).weights = floatArrayOf(1f, 0f)
        }

        applyAnimationPose(animation, mapOf(7 to firstPose), LayerBlendMode.Override, 1f)

        assertEquals(Vec3f(3f, 3f, 0f), first.localTransform.translation)
        assertEquals(Vec3f(1f, 0f, 0f), second.localTransform.translation)
        assertContentEquals(floatArrayOf(1f, 0f), firstPose.morphWeights)
        assertContentEquals(sourceWeights, secondPose.morphWeights)
        assertTrue(firstPose.isAnimated)
        val position = MutableVec3f()
        first.worldTransform.transform(position)
        assertEquals(Vec3f(3f, 3f, 0f), position)

        first.setEnabled(false)
        firstPose.resetPose()
        assertEquals(Vec3f(3f, 3f, 0f), first.localTransform.translation)
        animation.bone(7).translation = Vec3f(9f, 0f, 0f)
        applyAnimationPose(animation, mapOf(7 to firstPose), LayerBlendMode.Override, 1f)
        assertEquals(Vec3f(3f, 3f, 0f), first.localTransform.translation)
        first.setEnabled(true)
        firstPose.resetPose()
        assertEquals(Vec3f(1f, 0f, 0f), first.localTransform.translation)
        assertContentEquals(sourceWeights, firstPose.morphWeights)
        applyAnimationPose(animation, mapOf(7 to firstPose), LayerBlendMode.Override, 1f)
        assertEquals(Vec3f(10f, 0f, 0f), first.localTransform.translation)
    }

    @Test
    fun `model transforms reuse caller storage and exclude the scene owner`() {
        val scene = Scene(object : SceneContext { override val side = LogicalSide.SERVER })
        val owner = scene.create("owner")
        owner.localTransform.translate(100f, 0f, 0f)
        val root = owner.create("root")
        val middle = root.create("middle")
        val leaf = middle.create("leaf")
        root.localTransform.translate(1f, 0f, 0f).rotate(90f.deg, Vec3f.Z_AXIS)
        middle.localTransform.translate(0f, 2f, 0f).rotate(45f.deg, Vec3f.X_AXIS)
        leaf.localTransform.translate(0f, 0f, 3f)
        val rootPose = SceneModelPose(root, NodeDefinition(0, "root", mutableListOf(), TrsTransformF()))
        val middlePose = SceneModelPose(middle, NodeDefinition(1, "middle", mutableListOf(), TrsTransformF()), poseParent = rootPose)
        val leafPose = SceneModelPose(leaf, NodeDefinition(2, "leaf", mutableListOf(), TrsTransformF()), poseParent = middlePose)
        val expectedMatrix = MutableMat4f(root.localTransform.matrixF)
            .mul(middle.localTransform.matrixF).mul(leaf.localTransform.matrixF)
        val matrix = MutableMat4f()
        val rotation = MutableQuatF()

        assertSame(rootPose.poseTransform.matrixF, rootPose.modelMatrix())
        assertSame(matrix, leafPose.modelMatrix(matrix))
        for (row in 0..3) for (column in 0..3) {
            assertEquals(expectedMatrix[row, column], matrix[row, column], 0.0001f)
        }
        val expectedRotation = MutableQuatF(root.localTransform.rotation)
            .mul(middle.localTransform.rotation).mul(leaf.localTransform.rotation)
        assertSame(rotation, leafPose.modelRotation(rotation))
        assertEquals(1f, abs(expectedRotation.dot(rotation)), 0.0001f)
        assertFailsWith<IllegalArgumentException> { leafPose.modelRotation(root.localTransform.rotation) }

        root.localTransform.translate(2f, 0f, 0f)
        expectedMatrix.set(root.localTransform.matrixF).mul(middle.localTransform.matrixF).mul(leaf.localTransform.matrixF)
        assertSame(matrix, leafPose.modelMatrix(matrix))
        for (row in 0..3) for (column in 0..3) {
            assertEquals(expectedMatrix[row, column], matrix[row, column], 0.0001f)
        }
        scene.close()
    }
}
