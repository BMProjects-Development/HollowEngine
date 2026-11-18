package ru.hollowhorizon.hollowengine.client.models

import kotlinx.coroutines.flow.MutableStateFlow
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Mesh
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.ModelSceneInstance
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.Scene as ModelScene
import ru.hollowhorizon.hollowengine.client.models.internal.rig.BoneGeometry
import ru.hollowhorizon.hollowengine.client.models.internal.rig.ColliderRigGenerator
import ru.hollowhorizon.hollowengine.client.models.internal.rig.RigGenerationSource
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.scene.Scene
import ru.hollowhorizon.hollowengine.common.scene.SceneContext
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SceneRigGeneratorTest {
    @Test
    fun `collider generator measures scene geometry in the same bone space`() {
        val primitive = Primitive(
            positions = arrayOf(Vec3f(-0.2f, 0f, -0.2f), Vec3f(0.2f, 0.5f, 0.2f)),
            material = Material(),
        )
        val mesh = NodeDefinition(1, "mesh", mutableListOf(), TrsTransformF().translate(0f, 1f, 0f), Mesh(listOf(primitive), floatArrayOf()))
        val bone = NodeDefinition(0, "arm", mutableListOf(mesh), TrsTransformF().translate(3f, 0f, 0f))
        mesh.parent = bone
        val model = Model(0, listOf(ModelScene(listOf(bone))), emptySet())
        val scene = Scene(object : SceneContext { override val side = LogicalSide.SERVER })
        val instance = ModelSceneInstance.create(scene.create("owner"), "model", model)
        val legacy = ModelAttachment(MutableStateFlow(model), null)

        val sceneRig = ColliderRigGenerator.generate(RigGenerationSource(model, instance.poseNodes), ModelRig.EMPTY)
        val legacyRig = ColliderRigGenerator.generate(RigGenerationSource(model, legacy.poseTarget().poseNodesByIndex.values), ModelRig.EMPTY)
        assertEquals(legacyRig, sceneRig)
        assertNotNull(sceneRig.bone("arm"))
        val globals = BoneGeometry.bindGlobalsOf(instance.poseNodes.reversed())
        assertEquals(Vec3f(3f, 1f, 0f), globals.getValue(1).getTranslation())
        legacy.close()
        scene.close()
    }
}
