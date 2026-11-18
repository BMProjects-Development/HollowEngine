package ru.hollowhorizon.hollowengine.client.models

import kotlinx.coroutines.flow.MutableStateFlow
import ru.hollowhorizon.hollowengine.client.models.internal.Mesh
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.ModelSceneInstance
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.Scene as ModelScene
import ru.hollowhorizon.hollowengine.client.models.internal.Skin
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.MeshAttachment
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.scene.Scene
import ru.hollowhorizon.hollowengine.common.scene.SceneContext
import ru.hollowhorizon.hollowengine.common.scene.ScenePathSegment
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkinScenePoseTest {
    @Test
    fun `mesh attachment follows scene matrix visibility and skin without copying the pose`() {
        val skin = Skin(listOf(1), arrayOf<Mat4f>(MutableMat4f()))
        val joint = NodeDefinition(1, "joint", mutableListOf(), TrsTransformF().translate(1f, 0f, 0f))
        val mesh = NodeDefinition(2, "mesh", mutableListOf(), TrsTransformF().translate(0f, 2f, 0f), Mesh(listOf(Primitive(material = Material())), floatArrayOf()), skin)
        val root = NodeDefinition(0, "root", mutableListOf(joint, mesh), TrsTransformF())
        joint.parent = root
        mesh.parent = root
        val model = Model(0, listOf(ModelScene(listOf(root))), emptySet())
        val instance = ModelAttachment(MutableStateFlow(model), null)
        val host = instance.findNode("mesh")!!
        val attachment = host.attachments.filterIsInstance<MeshAttachment>().single()

        attachment.updateGlobalMatrix()
        assertSame(host.sceneObject.worldTransform, host.globalMatrix)
        assertMatrixEquals(host.sceneObject.worldTransform, attachment.globalMatrix)
        val matrices = attachment.skinMatrices()
        assertSame(matrices, attachment.skinMatrices())

        instance.sceneRoot.localTransform.translate(3f, 0f, 0f)
        attachment.updateGlobalMatrix()
        assertEquals(3f, attachment.globalMatrix.getTranslation().x)
        assertSame(matrices, attachment.skinMatrices())
        assertTrue(attachment.isVisible)
        instance.sceneRoot.setEnabled(false)
        assertFalse(attachment.isVisible)
        instance.close()
    }

    @Test
    fun `rendered meshes share the model node index and resolve joints in another root`() {
        val skin = Skin(listOf(0), arrayOf<Mat4f>(MutableMat4f()))
        val joint = NodeDefinition(0, "joint", mutableListOf(), TrsTransformF())
        val first = NodeDefinition(1, "first", mutableListOf(), TrsTransformF(), Mesh(emptyList(), floatArrayOf()), skin)
        val second = NodeDefinition(2, "second", mutableListOf(), TrsTransformF(), Mesh(emptyList(), floatArrayOf()), skin)
        val model = Model(0, listOf(ModelScene(listOf(joint, first, second))), emptySet())
        val attachment = ModelAttachment(MutableStateFlow(model), null)
        val jointNode = attachment.findNode("joint")!!
        val firstNode = attachment.findNode("first")!!
        val secondNode = attachment.findNode("second")!!

        assertSame(jointNode.transform, firstNode.modelPoseNodes.getValue(0).localTransform)
        assertSame(firstNode.modelPoseNodes, secondNode.modelPoseNodes)
        attachment.close()
    }

    @Test
    fun `skin matrices use scene poses and remain independent between model instances`() {
        val skin = Skin(listOf(1), arrayOf<Mat4f>(MutableMat4f()))
        val joint = NodeDefinition(1, "joint", mutableListOf(), TrsTransformF().translate(1f, 2f, 0f))
        val mesh = NodeDefinition(2, "mesh", mutableListOf(), TrsTransformF().translate(-2f, 0f, 0f), Mesh(emptyList(), floatArrayOf()), skin)
        val root = NodeDefinition(0, "root", mutableListOf(joint, mesh), TrsTransformF().translate(0f, 1f, 0f))
        joint.parent = root
        mesh.parent = root
        val model = Model(0, listOf(ModelScene(listOf(root))), emptySet())
        val scene = Scene(object : SceneContext { override val side = LogicalSide.SERVER })
        val owner = scene.create("owner")
        owner.localTransform.translate(10f, 20f, 30f)
        val first = ModelSceneInstance.create(owner, "first", model)
        val second = ModelSceneInstance.create(owner, "second", model)
        second.root.localTransform.translate(7f, 0f, 0f)
        val meshPath = listOf(ScenePathSegment("root"), ScenePathSegment("mesh"))
        val jointPath = listOf(ScenePathSegment("root"), ScenePathSegment("joint"))
        val firstMesh = first.node(meshPath)!!
        val secondMesh = second.node(meshPath)!!
        val firstSkin = skin.pose()
        val secondSkin = skin.pose()

        val rendered = renderModel(root)
        val runtimeNodes = rendered.poseTarget().poseNodesByIndex
        val legacyMatrices = skin.pose().compute(runtimeNodes.getValue(2).worldTransform, runtimeNodes)
        val firstMatrices = firstSkin.compute(firstMesh.worldTransform, first.poseTarget().poseNodesByIndex)
        val secondMatrices = secondSkin.compute(secondMesh.worldTransform, second.poseTarget().poseNodesByIndex)
        assertNotSame(firstMatrices, secondMatrices)
        assertMatrixEquals(legacyMatrices[0], firstMatrices[0])
        assertMatrixEquals(legacyMatrices[0], secondMatrices[0])

        first.node(jointPath)!!.localTransform.translate(1f, 0f, 0f)
        runtimeNodes.getValue(1).localTransform.translate(1f, 0f, 0f)
        val updatedLegacy = skin.pose().compute(runtimeNodes.getValue(2).worldTransform, runtimeNodes)
        assertSame(firstMatrices, firstSkin.compute(firstMesh.worldTransform, first.poseTarget().poseNodesByIndex))
        assertMatrixEquals(updatedLegacy[0], firstMatrices[0])
        assertMatrixEquals(legacyMatrices[0], secondMatrices[0])
        rendered.close()
        scene.close()
    }

    private fun assertMatrixEquals(expected: Mat4f, actual: Mat4f) {
        for (row in 0..3) for (column in 0..3) {
            assertEquals(expected[row, column], actual[row, column], 0.0001f, "matrix[$row,$column]")
        }
    }
}
