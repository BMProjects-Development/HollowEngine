package ru.hollowhorizon.hollowengine.client.models

import kotlinx.coroutines.flow.MutableStateFlow
import ru.hollowhorizon.hollowengine.client.models.bbmodel.BlockBenchModel
import ru.hollowhorizon.hollowengine.client.models.bbmodel.BlockBenchModelLoader
import ru.hollowhorizon.hollowengine.client.models.gltf.GltfFile
import ru.hollowhorizon.hollowengine.client.models.gltf.GltfModelLoader
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.ModelSpace
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Scene
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.common.scene.ScenePathSegment
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.*

class ModelNodePathsTest {
    @Test
    fun `gltf duplicate nodes retain source order on both logical sides`() {
        val source = """{"asset":{"version":"2.0"},"scene":0,
            "scenes":[{"nodes":[0]}],"nodes":[{"name":"body","children":[1,2]},
            {"name":"hand"},{"name":"hand"}]}"""
        val first = listOf(ScenePathSegment("body"), ScenePathSegment("hand"))
        val second = listOf(ScenePathSegment("body"), ScenePathSegment("hand", 2))
        for (side in ModelSide.entries) {
            val model = GltfModelLoader.load(GltfFile.fromJson(source), "test:model.gltf".rl, side)
            assertEquals(1, model.nodePaths.node(first)?.index)
            assertEquals(2, model.nodePaths.node(second)?.index)
        }
    }

    @Test
    fun `blockbench duplicate names resolve in source order for each instance`() {
        val source = """{"meta":{"format_version":"5.0"},"groups":[
            {"uuid":"body","name":"body"},
            {"uuid":"first","name":"hand"},
            {"uuid":"second","name":"hand"},
            {"uuid":"literal","name":"hand (2)"}],
            "outliner":[{"uuid":"body","children":[{"uuid":"first"},{"uuid":"second"},{"uuid":"literal"}]}]}"""
        val model =
            BlockBenchModelLoader.convert(BlockBenchModel.parse(source), "test:model.bbmodel".rl, ModelSide.SERVER)
        val body = ScenePathSegment("body")
        val firstPath = listOf(body, ScenePathSegment("hand"))
        val secondPath = listOf(body, ScenePathSegment("hand", 2))
        val literalPath = listOf(body, ScenePathSegment("hand (2)"))
        val clientModel =
            BlockBenchModelLoader.convert(BlockBenchModel.parse(source), "test:model.bbmodel".rl, ModelSide.CLIENT)
        val first = ModelAttachment(MutableStateFlow(model), null)
        val second = ModelAttachment(MutableStateFlow(model), null)
        try {
            val firstHand = first.findNode(firstPath)!!
            val secondHand = first.findNode(secondPath)!!
            val otherInstanceHand = second.findNode(secondPath)!!
            assertNotSame(firstHand, secondHand)
            assertNotSame(secondHand, otherInstanceHand)
            assertSame(secondHand.definition, otherInstanceHand.definition)
            assertSame(secondHand.definition, model.nodePaths.node(secondPath))
            assertEquals(secondPath, model.nodePaths.path(secondHand.definition))
            assertEquals("hand", clientModel.nodePaths.node(secondPath)?.name)
            assertNotSame(secondHand, first.findNode(literalPath))
            secondHand.transform.translate(1f, 0f, 0f)
            assertEquals(0f, otherInstanceHand.transform.translation.x)
            assertNull(first.findNode(listOf(body, ScenePathSegment("hand", 3))))
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `model-space correction is not part of authored paths`() {
        val node = NodeDefinition(0, "root", mutableListOf(), TrsTransformF())
        val roots = ModelSpace.place(listOf(node), facesPositiveZ = true, index = -7)
        val model = Model(0, listOf(Scene(roots)), emptySet())
        val path = listOf(ScenePathSegment("root"))
        assertSame(node, model.nodePaths.node(path))
        assertEquals(path, model.nodePaths.path(node))
        assertNull(model.nodePaths.path(roots.single()))
    }

    @Test
    fun `unnamed node fallback cannot collide with an authored name`() {
        val unnamed = NodeDefinition(0, null, mutableListOf(), TrsTransformF())
        val named = NodeDefinition(1, "Node_0", mutableListOf(), TrsTransformF())
        val model = Model(0, listOf(Scene(listOf(unnamed, named))), emptySet())
        assertSame(unnamed, model.nodePaths.node(listOf(ScenePathSegment("Node_0"))))
        assertSame(named, model.nodePaths.node(listOf(ScenePathSegment("Node_0", 2))))
    }
}
