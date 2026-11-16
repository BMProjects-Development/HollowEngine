package ru.hollowhorizon.hollowengine.client.models

import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockContext
import ru.hollowhorizon.hollowengine.client.models.internal.*
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationData
import ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations.Interpolator
import ru.hollowhorizon.hollowengine.client.models.internal.animator.*
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.models.internal.v2.applyRigPose
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.scene.Scene
import ru.hollowhorizon.hollowengine.common.scene.SceneContext
import ru.hollowhorizon.hollowengine.common.scene.ScenePathSegment
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.VfxInterpolation
import ru.hollowhorizon.hollowengine.common.vfx.VfxKey
import ru.hollowhorizon.hollowengine.common.vfx.VfxTrackCurve
import kotlin.test.*
import ru.hollowhorizon.hollowengine.client.models.internal.Scene as ModelScene

class ModelSceneInstanceTest {
    private fun scene() = Scene(object : SceneContext {
        override val side = LogicalSide.SERVER
    })

    @Test
    fun `model instances expose numbered authored nodes and keep poses independent`() {
        fun branch(syntheticIndex: Int, nodeIndex: Int, offset: Float): NodeDefinition {
            val hand = NodeDefinition(nodeIndex, "hand", mutableListOf(), TrsTransformF())
            return NodeDefinition(
                syntheticIndex,
                null,
                mutableListOf(hand),
                TrsTransformF().translate(offset, 0f, 0f)
            ).also { it.isSynthetic = true; hand.parent = it }
        }

        val model = Model(0, listOf(ModelScene(listOf(branch(0, 1, 2f), branch(2, 3, 5f)))), emptySet())
        val scene = scene()
        val owner = scene.create("owner")
        val first = ModelSceneInstance.create(owner, "first", model)
        val second = ModelSceneInstance.create(owner, "second", model)
        val path = listOf(ScenePathSegment("hand", 2))
        val firstHand = first.node(path)!!
        val secondHand = second.node(path)!!
        assertSame(first.root, firstHand.parent)
        assertEquals(2, first.root.children.size)
        assertNotEquals(firstHand.address, secondHand.address)
        assertEquals(5f, firstHand.worldTransform.getTranslation().x)
        assertEquals(5f, secondHand.worldTransform.getTranslation().x)

        val animation = AnimationPose().apply { bone(2).translation = Vec3f(3f, 0f, 0f) }
        firstHand.setEnabled(false)
        first.applyPose(animation, LayerBlendMode.Override, 1f)
        assertEquals(5f, firstHand.worldTransform.getTranslation().x)
        firstHand.setEnabled(true)
        first.applyPose(animation, LayerBlendMode.Override, 1f)
        assertEquals(8f, firstHand.worldTransform.getTranslation().x)
        assertEquals(5f, secondHand.worldTransform.getTranslation().x)
        first.root.setEnabled(false)
        first.resetPose()
        assertEquals(8f, firstHand.worldTransform.getTranslation().x)
        first.root.setEnabled(true)
        first.resetPose()
        assertEquals(5f, firstHand.worldTransform.getTranslation().x)
        first.close()
        assertTrue(firstHand.isRemoved)
        assertSame(secondHand, second.node(path))
        scene.close()
    }

    @Test
    fun `invalid model rolls back its entire scene instance`() {
        val node1 = NodeDefinition(0, "first", mutableListOf(), TrsTransformF())
        val node2 = NodeDefinition(0, "second", mutableListOf(), TrsTransformF())
        val model = Model(0, listOf(ModelScene(listOf(node1, node2))), emptySet())
        val scene = scene()
        val owner = scene.create("owner")
        assertFailsWith<IllegalArgumentException> { ModelSceneInstance.create(owner, "model", model) }
        assertTrue(owner.children.isEmpty())
        owner.create("model")
        scene.close()
    }

    @Test
    fun `synthetic transform keeps animating geometry when authored sibling is disabled`() {
        val bone = NodeDefinition(1, "bone", mutableListOf(), TrsTransformF())
        val geometry = NodeDefinition(
            3,
            null,
            mutableListOf(),
            TrsTransformF(),
            Mesh(emptyList(), floatArrayOf())
        ).also { it.isSynthetic = true }
        val geometryTransform = NodeDefinition(2, null, mutableListOf(geometry), TrsTransformF()).also {
                it.isSynthetic = true; geometry.parent = it
            }
        val sharedTransform = NodeDefinition(0, null, mutableListOf(bone, geometryTransform), TrsTransformF()).also {
                it.isSynthetic = true; bone.parent = it; geometryTransform.parent = it
            }
        val model = Model(0, listOf(ModelScene(listOf(sharedTransform))), emptySet())
        val scene = scene()
        val instance = ModelSceneInstance.create(scene.create("owner"), "model", model)
        val boneObject = instance.node(listOf(ScenePathSegment("bone")))!!
        boneObject.setEnabled(false)

        instance.applyPose(
            AnimationPose().apply { bone(0).translation = Vec3f(3f, 0f, 0f) },
            LayerBlendMode.Override,
            1f
        )

        assertEquals(3f, boneObject.worldTransform.getTranslation().x)
        scene.close()
    }

    @Test
    fun `animator applies clips masks and procedural layers to scene objects`() {
        val hand = NodeDefinition(0, "hand", mutableListOf(), TrsTransformF())
        val leg = NodeDefinition(1, "leg", mutableListOf(), TrsTransformF())
        val translation = object : Interpolator<Vec3f> {
            override val duration = 1f
            override fun compute(time: Float, context: BedrockContext) = Vec3f(2f, 0f, 0f)
        }
        val clip = AnimationClip(
            "move", mapOf(
                0 to AnimationData(translation, null, null, null),
                1 to AnimationData(translation, null, null, null),
            ), 1f
        )
        val model = Model(0, listOf(ModelScene(listOf(hand, leg))), emptySet(), listOf(clip))
        val scene = scene()
        val owner = scene.create("owner")
        val first = ModelSceneInstance.create(owner, "first", model)
        val second = ModelSceneInstance.create(owner, "second", model)
        val target = first.poseTarget()
        assertSame(target, first.poseTarget())
        assertEquals(setOf(0, 1), target.poseNodesByIndex.keys)
        assertEquals(setOf(0), target.mask(BoneMask.of("hand")))
        val animator = ModelAnimator().apply {
            add(
                ClipLayer(
                    ClipAnimationLayerSpec(
                        id = "clip",
                        animation = "move",
                        playMode = AnimationPlayMode.Loop,
                        mask = BoneMask.of("hand")
                    )
                )
            )
            add(
                ProceduralLayer(
                    ProceduralLayerSpec(
                        id = "procedural", transforms = listOf(
                            ProceduralBoneTransformSpec(
                                "hand",
                                translation = AnimationVectorExpression(x = AnimationExpression("3"))
                            )
                        )
                    )
                )
            )
        }

        first.resetPose()
        animator.applyTo(target, AnimatorEvaluationContext())
        assertEquals(5f, first.node(listOf(ScenePathSegment("hand")))!!.localTransform.translation.x)
        assertEquals(0f, first.node(listOf(ScenePathSegment("leg")))!!.localTransform.translation.x)
        assertEquals(0f, second.node(listOf(ScenePathSegment("hand")))!!.localTransform.translation.x)

        val montage = Montage(
            duration = 1f, bones = listOf(
                MontageBoneTrack(
                    "hand", MontageBoneProperty.POSITION, listOf(
                        VfxTrackCurve(0, keys = listOf(VfxKey(0f, 4f, VfxInterpolation.LINEAR)))
                    )
                )
            )
        )
        val montagePose = MontageSampler.sample(montage, 0f, target, target.mask(BoneMask.full()))
        assertEquals(4f, montagePose[0]!!.translation!!.x)

        first.node(listOf(ScenePathSegment("hand")))!!.setEnabled(false)
        animator.applyTo(target, AnimatorEvaluationContext())
        assertEquals(5f, first.node(listOf(ScenePathSegment("hand")))!!.localTransform.translation.x)
        scene.close()
    }

    @Test
    fun `legacy animation target retains node aliases and masks`() {
        val definition = NodeDefinition(0, "hand", mutableListOf(), TrsTransformF())
        val node = RuntimeNode(definition, null)
        val target = PoseTarget(mapOf(0 to node), emptyMap(), aliases = mapOf("rightHand" to "hand"))

        assertSame(node, target.node("rightHand"))
        assertEquals(0, target.nodeIndex("rightHand"))
        assertEquals(setOf(0), target.mask(BoneMask.of("hand")))
        assertSame(node, target.poseNodesByIndex[0])
    }

    @Test
    fun `rig pose is shared with legacy evaluation and respects disabled scene nodes`() {
        val definition = NodeDefinition(0, "hand", mutableListOf(), TrsTransformF())
        val model = Model(0, listOf(ModelScene(listOf(definition))), emptySet())
        val scene = scene()
        val instance = ModelSceneInstance.create(scene.create("owner"), "model", model)
        val hand = instance.node(listOf(ScenePathSegment("hand")))!!
        val legacy = RuntimeNode(definition, null)
        val rig = ModelRig(
            bones = mapOf(
                "hand" to RigBone(
                    alias = "rightHand",
                    pose = RigPose(position = Vec3f(2f, 3f, 4f))
                )
            )
        )

        val target = instance.poseTarget(rig)
        assertSame(target, instance.poseTarget(rig))
        assertEquals(0, target.nodeIndex("rightHand"))

        applyRigPose(listOf(legacy), rig)
        instance.applyRigPose(rig)
        assertEquals(legacy.transform.translation, hand.localTransform.translation)
        hand.setEnabled(false)
        instance.applyRigPose(rig)
        assertEquals(Vec3f(2f, 3f, 4f), hand.localTransform.translation)
        scene.close()
    }

    @Test
    fun `legacy only constraints are reported instead of silently skipped`() {
        val definition = NodeDefinition(0, "hand", mutableListOf(), TrsTransformF())
        val model =
            Model(0, listOf(ModelScene(listOf(definition))), emptySet(), constraints = listOf(ModelPoseConstraint { }))
        val scene = scene()
        val instance = ModelSceneInstance.create(scene.create("owner"), "model", model)

        val error = assertFailsWith<IllegalStateException> { instance.applyAuthoredConstraints() }
        assertTrue(error.message!!.contains("do not support scene-owned poses"))
        scene.close()
    }
}
