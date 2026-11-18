import ru.hollowhorizon.hollowengine.client.models.renderModelNode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.internal.*
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.applyAnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelInstance
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelInstanceMaterials
import ru.hollowhorizon.hollowengine.client.models.internal.v2.NestedModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.BaseAttachment
import ru.hollowhorizon.hollowengine.common.attachments.components.AnimationsComponent
import ru.hollowhorizon.hollowengine.common.models.AnimationPlayMode
import ru.hollowhorizon.hollowengine.common.models.ClipAnimationLayerSpec
import ru.hollowhorizon.hollowengine.common.models.LayerBlendMode
import ru.hollowhorizon.hollowengine.common.models.ModelAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.models.RigBoneOrigin
import ru.hollowhorizon.hollowengine.common.scene.ScenePathSegment
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelInstanceIsolationTests {
    @Test
    fun `settled rendered model follows an external parent transform`() {
        val parent = object : BaseAttachment() {}
        parent.updateGlobalMatrix()
        val attachment = ModelAttachment(MutableStateFlow(modelWith(Material(), floatArrayOf())), parent)
        val instance = ModelInstance(attachment)
        instance.update(contextAt(0f), frame = 0L)
        val node = attachment.findNode("Root")!!

        parent.transform.translate(4f, 0f, 0f)
        parent.updateGlobalMatrix()
        instance.update(contextAt(1f), frame = 1L)

        assertEquals(4f, node.globalMatrix.getTranslation().x)
        instance.close()
    }

    @Test
    fun `removed model scene is not recreated by resource or rig updates`() {
        val flow = MutableStateFlow(modelWith(Material(), floatArrayOf()))
        val attachment = ModelAttachment(flow, null)
        val instance = ModelInstance(attachment)
        val root = attachment.sceneRoot
        instance.update(contextAt(0f), frame = 0L)
        root.remove()
        flow.value = modelWith(Material(), floatArrayOf())

        instance.update(contextAt(1f), frame = 1L)
        attachment.ensureReady()
        attachment.rig = ModelRig(bones = mapOf("extra" to RigBone(origin = RigBoneOrigin())))

        assertTrue(root.isRemoved)
        assertNull(attachment.findNode("Root"))
        instance.close()
    }

    @Test
    fun `scene activity and removal control rendered node resources`() {
        val child = NodeDefinition(1, "child", mutableListOf(), TrsTransformF())
        val parent = NodeDefinition(0, "parent", mutableListOf(child), TrsTransformF())
        child.parent = parent
        val attachment = ModelAttachment(MutableStateFlow(Model(0, listOf(Scene(listOf(parent))), emptySet())), null)
        val parentNode = attachment.findNode("parent")!!
        val childNode = attachment.findNode("child")!!
        val childObject = childNode.sceneObject
        val events = mutableListOf<String>()
        childNode.attachments.add(object : BaseAttachment(childNode) {
            override fun onSceneActiveChanged(active: Boolean) { events += if (active) "resume" else "pause" }
            override fun close() { events += "close" }
        })
        val snapshot = parentNode.children
        assertSame(snapshot, parentNode.children)
        assertSame(childNode, snapshot.single())

        childObject.setEnabled(false)
        childObject.setEnabled(true)
        childObject.remove()

        assertEquals(listOf("pause", "resume", "close"), events)
        assertTrue(parentNode.children.isEmpty())
        assertNull(attachment.findNode("child"))
        attachment.close()
        assertEquals(listOf("pause", "resume", "close"), events)
    }

    @Test
    fun `nested model scene survives parent asset and rig rebuilds`() {
        NestedModelAttachment.register()
        fun model(offset: Float): Model {
            val hand = NodeDefinition(1, "hand", mutableListOf(), TrsTransformF())
            val pivot = NodeDefinition(0, null, mutableListOf(hand), TrsTransformF().translate(offset, 0f, 0f)).also {
                it.isSynthetic = true
                hand.parent = it
            }
            return Model(0, listOf(Scene(listOf(pivot))), emptySet())
        }
        val flow = MutableStateFlow(model(1f))
        val attachment = ModelAttachment(flow, null)
        val sword = ModelAttachmentSpec(id = "sword")
        val rig = ModelRig(bones = mapOf("hand" to RigBone(attachments = listOf(sword))))
        attachment.rig = rig
        val first = attachment.findNode("hand")!!.attachments.filterIsInstance<NestedModelAttachment>().single()
        val nestedRoot = first.model.sceneRoot
        val addition = nestedRoot.create("added")
        flow.value = model(3f)

        attachment.ensureReady()

        val afterReload = attachment.findNode("hand")!!.attachments.filterIsInstance<NestedModelAttachment>().single()
        assertSame(first, afterReload)
        assertSame(nestedRoot, afterReload.model.sceneRoot)
        assertSame(addition, nestedRoot.children.single())

        attachment.rig = rig.copy(bones = rig.bones + ("extra" to RigBone(origin = RigBoneOrigin("hand"))))
        val afterRigChange = attachment.findNode("hand")!!.attachments.filterIsInstance<NestedModelAttachment>().single()
        assertSame(first, afterRigChange)
        assertSame(addition, nestedRoot.children.single())
        attachment.findNode("hand")!!.sceneObject.remove()
        assertTrue(nestedRoot.isRemoved)
        assertNull(attachment.findNode("hand"))
        attachment.close()
    }

    @Test
    fun `rendered model uses scene transforms and preserves additions through reload`() {
        fun model(offset: Float): Model {
            val hand = NodeDefinition(1, "hand", mutableListOf(), TrsTransformF())
            val pivot = NodeDefinition(0, null, mutableListOf(hand), TrsTransformF().translate(offset, 0f, 0f)).also {
                it.isSynthetic = true
                hand.parent = it
            }
            return Model(0, listOf(Scene(listOf(pivot))), emptySet())
        }
        val flow = MutableStateFlow(model(2f))
        val attachment = ModelAttachment(flow, null)
        val instance = ModelInstance(attachment)
        val root = attachment.sceneRoot
        val hand = root.scene.resolve(root.address.copy(importedPath = listOf(ScenePathSegment("hand"))))!!
        val sword = hand.create("sword")
        val node = attachment.findNode("hand")!!
        assertSame(hand.localTransform, node.transform)
        assertSame(hand.worldTransform, node.globalMatrix)

        instance.update(contextAt(0f), frame = 0L)
        hand.localTransform.translate(0f, 3f, 0f)
        instance.update(contextAt(1f), frame = 1L)
        assertEquals(Vec3f(2f, 3f, 0f), node.globalMatrix.getTranslation())
        hand.setEnabled(false)
        instance.update(contextAt(2f), frame = 2L)
        assertFalse(node.isVisible)
        hand.setEnabled(true)

        flow.value = model(5f)
        instance.update(contextAt(3f), frame = 3L)
        assertSame(hand, root.scene.resolve(hand.address))
        assertSame(sword, hand.children.single())
        assertSame(hand.localTransform, attachment.findNode("hand")!!.transform)
        assertEquals(5f, attachment.findNode("hand")!!.globalMatrix.getTranslation().x)
        instance.close()
    }

    @Test
    fun `material changes stay inside one model instance`() {
        val sourceMaterial = Material(texture = "test:source".rl)
        val model = modelWith(sourceMaterial, floatArrayOf())
        val first = ModelInstanceMaterials(model)
        val second = ModelInstanceMaterials(model)

        first.values.single().texture = "test:replacement".rl

        assertNotSame(sourceMaterial, first.values.single())
        assertEquals("test:source".rl, sourceMaterial.texture)
        assertEquals("test:source".rl, second.values.single().texture)
    }

    @Test
    fun `morph weights stay inside one runtime node`() {
        val sourceWeights = floatArrayOf(0.25f, 0.75f)
        val definition = modelWith(Material(), sourceWeights).scenes.single().nodes.single()
        val first = renderModelNode(definition, null)
        val second = renderModelNode(definition, null)
        val pose = AnimationPose().apply {
            bone(definition.index).weights = floatArrayOf(1f, 0f)
        }

        applyAnimationPose(
            pose = pose,
            nodes = mapOf(definition.index to first),
            blendMode = LayerBlendMode.Override,
            weight = 1f,
        )

        assertContentEquals(floatArrayOf(1f, 0f), first.morphWeights)
        assertContentEquals(sourceWeights, second.morphWeights)
        assertContentEquals(sourceWeights, definition.mesh!!.primitives.single().weights)
    }

    @Test
    fun `skin poses keep joint matrices per instance and reuse their storage`() {
        val skin = Skin(listOf(0), arrayOf<Mat4f>(MutableMat4f().translate(Vec3f(-1f, 0f, 0f))))
        val definition = NodeDefinition(0, "joint", mutableListOf(), TrsTransformF())
        val firstJoint = renderModelNode(definition, null)
        val secondJoint = renderModelNode(definition, null)
        firstJoint.transform.translate(5f, 0f, 0f)
        secondJoint.transform.translate(9f, 0f, 0f)
        firstJoint.updateGlobalMatrix()
        secondJoint.updateGlobalMatrix()
        val root = MutableMat4f().translate(Vec3f(2f, 0f, 0f))
        val first = skin.pose()
        val second = skin.pose()

        val firstMatrices = first.compute(root, mapOf(0 to firstJoint))
        val firstMatrix = firstMatrices[0]
        val secondMatrices = second.compute(root, mapOf(0 to secondJoint))
        assertNotSame(firstMatrices, secondMatrices)
        assertNotSame(firstMatrix, secondMatrices[0])
        assertEquals(2f, firstMatrix.m30)
        assertEquals(6f, secondMatrices[0].m30)

        firstJoint.transform.translate(2f, 0f, 0f)
        firstJoint.updateGlobalMatrix()
        assertSame(firstMatrices, first.compute(root, mapOf(0 to firstJoint)))
        assertSame(firstMatrix, firstMatrices[0])
        assertEquals(4f, firstMatrix.m30)
        assertEquals(6f, secondMatrices[0].m30)
    }

    @Test
    fun `model animation advances once per render frame`() {
        val animation = AnimationClip(name = "clock", nodes = emptyMap(), duration = 10f)
        val model = Model(
            scene = 0,
            scenes = listOf(
                Scene(
                    listOf(
                        NodeDefinition(
                            index = 0,
                            name = "Root",
                            children = mutableListOf(),
                            transform = TrsTransformF(),
                        )
                    )
                )
            ),
            materials = emptySet(),
            animations = listOf(animation),
        )
        val instance = ModelInstance(ModelAttachment(MutableStateFlow(model), null))
        instance.animator.configure(
            model = null, animations = AnimationsComponent(
                clips = listOf(
                    ClipAnimationLayerSpec(
                        id = "clock",
                        animation = animation.name,
                        playMode = AnimationPlayMode.Loop,
                    )
                )
            )
        )

        val step = 20f / 60f
        repeat(31) { frame -> instance.update(contextAt(frame * step), frame = frame.toLong()) }
        val halfway = instance.animator.layerTime("clock")!!

        instance.update(contextAt(60f), frame = 30L)

        instance.update(contextAt(30 * step), frame = 31L)

        assertEquals(0.5f, halfway, 0.0001f)
        assertEquals(halfway, instance.animator.layerTime("clock")!!, 0.0001f)
    }

    @Test
    fun `settled model rebuilds when its resource changes`() {
        val original = modelWith(Material(), floatArrayOf())
        val replacement = modelWith(Material(), floatArrayOf())
        val flow = MutableStateFlow(original)
        val attachment = ModelAttachment(flow, null)
        val instance = ModelInstance(attachment)
        instance.update(contextAt(0f), frame = 0L)

        flow.value = replacement
        instance.update(contextAt(1f), frame = 1L)

        assertSame(replacement, attachment.model)
        instance.close()
    }

    private fun contextAt(ticks: Float) = AnimatorEvaluationContext().also { it.time = ticks }

    private fun modelWith(material: Material, morphWeights: FloatArray): Model {
        val primitive = Primitive(
            material = material,
            morphTargets = List(morphWeights.size) { emptyMap() },
            weights = morphWeights.copyOf(),
        )
        val node = NodeDefinition(
            index = 0,
            name = "Root",
            children = mutableListOf(),
            transform = TrsTransformF(),
            mesh = Mesh(listOf(primitive), morphWeights.copyOf()),
        )
        return Model(
            scene = 0,
            scenes = listOf(Scene(listOf(node))),
            materials = setOf(material),
        )
    }
}
