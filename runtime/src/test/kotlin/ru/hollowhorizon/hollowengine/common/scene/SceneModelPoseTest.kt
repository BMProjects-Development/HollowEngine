package ru.hollowhorizon.hollowengine.common.scene

import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Mesh
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.applyAnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.models.LayerBlendMode
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.*

class SceneModelPoseTest {
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
            val existing = RuntimeNode(definition, null)
            node.localTransform.set(definition.baseTransform)
            val scenePose = SceneModelPose(node, definition.baseTransform, weights)
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
        val firstPose = SceneModelPose(first, rest, sourceWeights)
        val secondPose = SceneModelPose(second, rest, sourceWeights)
        assertFailsWith<IllegalArgumentException> { SceneModelPose(first, first.localTransform) }
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
}
