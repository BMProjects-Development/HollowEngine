package ru.hollowhorizon.hollowengine.client.models

import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockContext
import ru.hollowhorizon.hollowengine.client.models.internal.*
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationData
import ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations.Interpolator
import ru.hollowhorizon.hollowengine.client.models.internal.animator.*
import ru.hollowhorizon.hollowengine.client.models.internal.v2.applyRigPose
import ru.hollowhorizon.hollowengine.client.models.internal.v2.applyRigConstraintsToPose
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
    fun `reload keeps additions at matching paths through changed synthetic pivots`() {
        fun model(offset: Float, includeLeg: Boolean): Model {
            val hand = NodeDefinition(1, "hand", mutableListOf(), TrsTransformF())
            val pivot = NodeDefinition(0, null, mutableListOf(hand), TrsTransformF().translate(offset, 0f, 0f)).also {
                it.isSynthetic = true
                hand.parent = it
            }
            val nodes = mutableListOf(pivot)
            if (includeLeg) nodes += NodeDefinition(2, "leg", mutableListOf(), TrsTransformF())
            return Model(0, listOf(ModelScene(nodes)), emptySet())
        }
        val scene = scene()
        val instance = ModelSceneInstance.create(scene.create("owner"), "model", model(2f, true))
        val hand = instance.node(listOf(ScenePathSegment("hand")))!!
        val sword = hand.create("sword")
        val removedLeg = instance.node(listOf(ScenePathSegment("leg")))!!.address

        val removed = instance.reload(model(5f, false))

        assertEquals(listOf(removedLeg), removed)
        assertSame(hand, instance.node(listOf(ScenePathSegment("hand"))))
        assertSame(sword, hand.children.single())
        assertEquals(5f, hand.worldTransform.getTranslation().x)
        assertNull(scene.resolve(removedLeg))
        scene.close()
    }

    @Test
    fun `reload validates new paths before changing the instance`() {
        val first = NodeDefinition(0, "hand", mutableListOf(), TrsTransformF())
        val original = Model(0, listOf(ModelScene(listOf(first))), emptySet())
        val scene = scene()
        val instance = ModelSceneInstance.create(scene.create("owner"), "model", original)
        val hand = instance.node(listOf(ScenePathSegment("hand")))!!
        val added = instance.root.create("sword")
        val replacement = Model(0, listOf(ModelScene(listOf(
            NodeDefinition(0, "hand", mutableListOf(), TrsTransformF()),
            NodeDefinition(1, "sword", mutableListOf(), TrsTransformF()),
        ))), emptySet())

        assertFailsWith<IllegalArgumentException> { instance.reload(replacement) }

        assertSame(original, instance.model)
        assertSame(hand, instance.node(listOf(ScenePathSegment("hand"))))
        assertSame(added, instance.root.children.single { it.name == "sword" })
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
        val node = renderModelNode(definition, null)
        val target = PoseTarget(mapOf(0 to node), emptyMap(), aliases = mapOf("rightHand" to "hand"))

        assertSame(node, target.node("rightHand"))
        assertEquals(0, target.nodeIndex("rightHand"))
        assertEquals(setOf(0), target.mask(BoneMask.of("hand")))
        assertSame(node, target.poseNodesByIndex[0])
        assertFailsWith<IllegalArgumentException> { PoseTarget(mapOf(1 to node), emptyMap()) }
    }

    @Test
    fun `rig pose is shared with legacy evaluation and respects disabled scene nodes`() {
        val definition = NodeDefinition(0, "hand", mutableListOf(), TrsTransformF())
        val model = Model(0, listOf(ModelScene(listOf(definition))), emptySet())
        val scene = scene()
        val instance = ModelSceneInstance.create(scene.create("owner"), "model", model)
        val hand = instance.node(listOf(ScenePathSegment("hand")))!!
        val legacy = renderModelNode(definition, null)
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
    fun `ik reaches its target on scene poses and skips disabled chains`() {
        val foot = NodeDefinition(2, "foot", mutableListOf(), TrsTransformF().translate(0f, -1f, 0f))
        val knee = NodeDefinition(1, "knee", mutableListOf(foot), TrsTransformF().translate(0f, -1f, 0.01f))
        val hip = NodeDefinition(0, "hip", mutableListOf(knee), TrsTransformF())
        foot.parent = knee
        knee.parent = hip
        val model = Model(0, listOf(ModelScene(listOf(hip))), emptySet())
        val scene = scene()
        val instance = ModelSceneInstance.create(scene.create("owner"), "model", model)
        val goal = Vec3f(0.3f, -1.5f, 0.4f)
        val rig = ModelRig(
            bones = mapOf("foot" to RigBone(attachments = listOf(IkChainSpec(id = "leg", target = "step")))),
            attachments = listOf(IkTargetSpec(id = "step", offset = goal)),
        )
        val footObject = instance.node(listOf(ScenePathSegment("hip"), ScenePathSegment("knee"), ScenePathSegment("foot")))!!

        applyRigConstraintsToPose(instance.poseNodes, rig, AnimatorEvaluationContext())
        assertTrue(goal.distance(footObject.worldTransform.getTranslation()) < 1e-3f)

        instance.resetPose()
        footObject.setEnabled(false)
        applyRigConstraintsToPose(instance.poseNodes, rig, AnimatorEvaluationContext())
        assertEquals(Vec3f(0f, -1f, 0.01f), instance.node(listOf(ScenePathSegment("hip"), ScenePathSegment("knee")))!!.localTransform.translation)
        scene.close()
    }

    @Test
    fun `model constraints apply once per scene pose and run again after reset`() {
        val definition = NodeDefinition(0, "hand", mutableListOf(), TrsTransformF())
        var calls = 0
        val model =
            Model(0, listOf(ModelScene(listOf(definition))), emptySet(), constraints = listOf(ModelPoseConstraint { nodes ->
                assertEquals(setOf(0), nodes.keys)
                calls++
            }))
        val scene = scene()
        val instance = ModelSceneInstance.create(scene.create("owner"), "model", model)

        instance.applyAuthoredConstraints()
        instance.applyAuthoredConstraints()
        assertEquals(1, calls)
        instance.resetPose()
        instance.applyAuthoredConstraints()
        assertEquals(2, calls)
        scene.close()
    }
}
