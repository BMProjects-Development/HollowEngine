package ru.hollowhorizon.hollowengine.client.gui.scripting

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import ru.hollowhorizon.hollowengine.client.ui.ide.recipe.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class VanillaRecipeShapesTest {
    @Test
    fun `shaped to shapeless keeps the ingredients row by row and drops the grid`() {
        val shaped = recipe(
            """{"type": "minecraft:crafting_shaped", "category": "building", "group": "g",
                "pattern": ["I I", " S "], "key": {"I": {"item": "minecraft:iron_ingot"}, "S": {"item": "minecraft:stick"}},
                "result": {"id": "minecraft:bucket", "count": 2}}""",
        )

        val shapeless = convert(shaped, ShapedShape, ShapelessShape)

        assertEquals(
            listOf("minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:stick"),
            shapeless.getAsJsonArray("ingredients").map { it.asJsonObject.get("item").asString },
        )
        assertEquals(2, shapeless.getAsJsonObject("result").get("count").asInt)
        assertEquals("building", shapeless.get("category").asString)
        assertEquals("g", shapeless.get("group").asString)
        assertFalse(shapeless.has("pattern"))
        assertFalse(shapeless.has("key"))
    }

    @Test
    fun `shapeless to shaped fills the grid row by row and shares a key between equal ingredients`() {
        val shapeless = recipe(
            """{"type": "minecraft:crafting_shapeless", "ingredients": [
                {"item": "minecraft:stick"}, {"item": "minecraft:stick"}, {"item": "minecraft:iron_ingot"},
                {"item": "minecraft:coal"}], "result": {"id": "minecraft:torch"}}""",
        )

        val shaped = convert(shapeless, ShapelessShape, ShapedShape)

        assertEquals(listOf("##X", "I  "), shaped.getAsJsonArray("pattern").map { it.asString })
        assertEquals(setOf("#", "X", "I"), shaped.getAsJsonObject("key").keySet())
    }

    @Test
    fun `crafting to smelting keeps one input, drops the count and a category the furnace has not`() {
        val shapeless = recipe(
            """{"type": "minecraft:crafting_shapeless", "category": "building",
                "ingredients": [{"item": "minecraft:sand"}, {"item": "minecraft:dirt"}],
                "result": {"id": "minecraft:glass", "count": 4}}""",
        )

        val smelting = convert(shapeless, ShapelessShape, CookingShape)

        assertEquals("minecraft:sand", smelting.getAsJsonObject("ingredient").get("item").asString)
        assertFalse(smelting.getAsJsonObject("result").has("count"))
        assertFalse(smelting.has("category"))
    }

    @Test
    fun `one furnace to another keeps experience and cooking time`() {
        val smelting = recipe(
            """{"type": "minecraft:smelting", "category": "food", "ingredient": {"item": "minecraft:beef"},
                "result": {"id": "minecraft:cooked_beef"}, "experience": 0.35, "cookingtime": 200}""",
        )

        val smoking = convert(smelting, CookingShape, CookingShape)

        assertEquals(0.35f, smoking.get("experience").asFloat)
        assertEquals(200, smoking.get("cookingtime").asInt)
        assertEquals("food", smoking.get("category").asString)
    }

    @Test
    fun `a trim has no result to carry`() {
        val transform = recipe(
            """{"type": "minecraft:smithing_transform", "template": {"item": "minecraft:netherite_upgrade_smithing_template"},
                "base": {"item": "minecraft:diamond_sword"}, "addition": {"item": "minecraft:netherite_ingot"},
                "result": {"id": "minecraft:netherite_sword"}}""",
        )

        val trim = convert(transform, SmithingShape(hasResult = true), SmithingShape(hasResult = false))

        assertEquals("minecraft:diamond_sword", trim.getAsJsonObject("base").get("item").asString)
        assertNull(trim.get("result"))
    }

    private fun convert(recipe: JsonObject, from: RecipeShape, to: RecipeShape): JsonObject =
        JsonObject().also { to.write(it, from.read(recipe), recipe) }

    private fun recipe(json: String): JsonObject = JsonParser.parseString(json).asJsonObject
}
