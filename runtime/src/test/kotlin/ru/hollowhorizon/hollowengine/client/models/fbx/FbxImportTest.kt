package ru.hollowhorizon.hollowengine.client.models.fbx

import net.minecraft.resources.ResourceLocation
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import kotlin.math.abs
import kotlin.test.assertFailsWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FbxImportTest {
    private fun document(file: String): Document = FbxModelLoader.parse(
        javaClass.getResourceAsStream("/assets/hollowengine/models/entity/$file")!!.use { it.readBytes() }
    )

    @ParameterizedTest
    @ValueSource(strings = ["player_model.fbx", "player_model_binary.fbx", "wolf.fbx"])
    fun `reference files convert to complete renderable models`(file: String) {
        val document = document(file)
        val model = document.convert(ResourceLocation.fromNamespaceAndPath("hollowengine", "models/entity/$file"), ModelSide.SERVER)
        val nodes = model.scenes.flatMap { it.nodes }.flatMap { it.allBones() }
        val primitives = nodes.flatMap { it.mesh?.primitives.orEmpty() }
        assertTrue(primitives.isNotEmpty())
        assertEquals(nodes.size, nodes.map { it.index }.distinct().size)
        primitives.forEach { primitive ->
            val positions = assertNotNull(primitive.positions)
            assertTrue(positions.isNotEmpty())
            val indices = assertNotNull(primitive.indices)
            assertEquals(0, indices.size % 3)
            assertTrue(indices.all { it in positions.indices })
            primitive.normals?.let { assertEquals(positions.size, it.size) }
            primitive.texCoords?.let { assertEquals(positions.size, it.size) }
            primitive.joints?.let { assertEquals(positions.size, it.size) }
            primitive.jointWeights?.forEach { assertEquals(1f, it.x + it.y + it.z + it.w, 1e-5f) }
        }
        assertTrue(model.materials.isNotEmpty())
        if (file == "wolf.fbx") {
            val skinned = nodes.filter { it.skin != null }
            assertTrue(skinned.isNotEmpty())
            skinned.forEach { node ->
                val skin = node.skin!!
                assertTrue(skin.jointsIds.all { joint -> nodes.any { it.index == joint } })
                assertTrue(node.mesh!!.primitives.all { it.hasSkinning && it.jointCount == skin.jointsIds.size })
            }
        } else if (file == "player_model.fbx") assertEquals(60, model.animations.size)
        else {
            assertTrue(document.warnings.any { it.message.contains("NaN") })
            assertTrue(model.materials.any { it.texture.path == "models/entity/classic.png" })
        }
    }

    @Test
    fun `strict import reports invalid binary animation times`() {
        val document = document("player_model_binary.fbx")
        assertFailsWith<FbxImportException> {
            document.convert(ResourceLocation.fromNamespaceAndPath("test", "model.fbx"), ModelSide.SERVER,
                FbxImportOptions(InvalidAnimationPolicy.REJECT))
        }
    }

    @Test
    fun `wolf bind matrices preserve the mesh in the source bind pose`() {
        val document = document("wolf.fbx")
        val model = document.convert(ResourceLocation.fromNamespaceAndPath("test", "wolf.fbx"), ModelSide.SERVER)
        val globals = mutableMapOf<Int, MutableMat4f>()
        fun global(index: Int): MutableMat4f = globals.getOrPut(index) {
            val node = model.nodes.single { it.index == index }
            MutableMat4f(node.parent?.let { global(it.index) } ?: MutableMat4f()).mul(node.localMatrix)
        }
        for (node in model.nodes.filter { it.skin != null }) {
            val skin = node.skin!!
            for (joint in skin.jointsIds.indices) {
                val result = MutableMat4f(global(skin.jointsIds[joint])).mul(skin.inverseBindMatrices[joint])
                val expected = global(node.index)
                for (row in 0..3) for (column in 0..3) {
                    assertTrue(abs(result[row, column] - expected[row, column]) < 0.02f,
                        "${node.name}, joint $joint at $row,$column: ${result[row, column]} vs ${expected[row, column]}")
                }
            }
        }
    }

    @Test
    fun `ASCII and binary exports retain identical geometry`() {
        val ascii = document("player_model.fbx")
        val binary = document("player_model_binary.fbx")
        fun meshes(document: Document) = document.objects.values
            .filter { it.element.keyToken.stringContents == "Geometry" && it.element[2].parseAsString == "Mesh" }
            .map { assertNotNull(it.get<MeshGeometry>()) }.associateBy { it.name }
        val left = meshes(ascii)
        val right = meshes(binary)
        assertEquals(left.keys, right.keys)
        left.forEach { (name, mesh) ->
            val other = right.getValue(name)
            assertEquals(mesh.vertices, other.vertices, name)
            assertEquals(mesh.indices, other.indices, name)
            assertEquals(mesh.normals, other.normals, name)
            assertEquals(mesh.uvs[0], other.uvs[0], name)
            assertContentEquals(mesh.materials.toIntArray(), other.materials.toIntArray(), name)
        }
    }
}
