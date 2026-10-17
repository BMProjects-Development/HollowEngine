import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.internal.*
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelInstanceMaterials
import ru.hollowhorizon.hollowengine.common.models.MaterialSource
import ru.hollowhorizon.hollowengine.common.models.ModelMetadata
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Materials are addressed by name, so the names have to survive loading, renaming and hot reloads, and
 * an override has to stay an override of one instance, never of the model everyone else shares.
 */
class ModelMaterialTests {
    @Test
    fun `a material declared once and used by a mesh is listed once`() {
        val shared = Material(name = "Body")
        val model = modelWith(listOf(shared), listOf(shared, Material(name = "Eyes")))

        assertEquals(listOf("Body", "Eyes"), model.allMaterials().map(Material::name))
    }

    @Test
    fun `metadata renames the materials it names and leaves the rest alone`() {
        val model = modelWith(listOf(Material(name = "Body"), Material(name = "material_1")))

        model.renameMaterials(ModelMetadata(materials = mapOf("skin" to "Body", "cape" to "material_1")))

        assertEquals(listOf("skin", "cape"), model.allMaterials().map(Material::name))
    }

    @Test
    fun `a name no material has changes nothing`() {
        val model = modelWith(listOf(Material(name = "Body")))

        model.renameMaterials(ModelMetadata(materials = mapOf("skin" to "Torso")))

        assertEquals(listOf("Body"), model.allMaterials().map(Material::name))
    }

    @Test
    fun `an override dresses one instance and not the model`() {
        val source = Material(name = "skin", texture = "test:original".rl)
        val model = modelWith(listOf(source))
        val dressed = ModelInstanceMaterials(model)
        val untouched = ModelInstanceMaterials(model)

        dressed.apply(mapOf("skin" to MaterialSource.Texture(texture = "test:replacement".rl)))

        assertEquals("test:replacement".rl, dressed.byName.getValue("skin").texture)
        assertEquals("test:original".rl, untouched.byName.getValue("skin").texture)
        assertEquals("test:original".rl, source.texture)
    }

    /** Applying is a full statement of the looks, so dropping a name undoes it. */
    @Test
    fun `a dropped override goes back to the model's own texture`() {
        val model = modelWith(listOf(Material(name = "skin", texture = "test:original".rl)))
        val materials = ModelInstanceMaterials(model)
        materials.apply(mapOf("skin" to MaterialSource.Texture(texture = "test:replacement".rl)))

        materials.apply(emptyMap())

        assertEquals("test:original".rl, materials.byName.getValue("skin").texture)
    }

    @Test
    fun `an override for a name the model does not have is ignored`() {
        val model = modelWith(listOf(Material(name = "skin", texture = "test:original".rl)))
        val materials = ModelInstanceMaterials(model)

        materials.apply(mapOf("cape" to MaterialSource.Texture(texture = "test:cape".rl)))

        assertNull(materials.byName["cape"])
        assertEquals("test:original".rl, materials.byName.getValue("skin").texture)
        assertTrue(materials.values.none { it.texture == "test:cape".rl })
    }

    private fun modelWith(declared: List<Material>, onPrimitives: List<Material> = declared): Model {
        val node = NodeDefinition(
            index = 0,
            name = "Root",
            children = mutableListOf(),
            transform = TrsTransformF(),
            mesh = Mesh(onPrimitives.map { Primitive(material = it) }, floatArrayOf()),
        )
        return Model(
            scene = 0,
            scenes = listOf(Scene(listOf(node))),
            materials = declared.toSet(),
        )
    }
}
