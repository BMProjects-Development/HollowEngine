package ru.hollowhorizon.hollowengine.client.ui.ide.recipe

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal val CraftingCategories = listOf("building", "redstone", "equipment", "misc")
internal val CookingCategories = listOf("food", "blocks", "misc")

private const val MaxCraftingIngredients = 9

internal object ShapedShape : RecipeShape {
    override fun read(json: JsonObject) =
        RecipeContents(shapedCells(json)?.filterNotNull().orEmpty(), json.get("result"))

    override fun write(target: JsonObject, contents: RecipeContents, previous: JsonObject) {
        target.copyShared(previous, CraftingCategories)
        target.writeShaped(List(GridSize * GridSize) { contents.ingredients.getOrNull(it) })
        target.put("result", contents.result)
        target.copy(previous, "show_notification")
    }
}

internal object ShapelessShape : RecipeShape {
    override fun read(json: JsonObject) = RecipeContents(shapelessIngredients(json), json.get("result"))

    override fun write(target: JsonObject, contents: RecipeContents, previous: JsonObject) {
        target.copyShared(previous, CraftingCategories)
        target.add(
            "ingredients",
            JsonArray().apply { contents.ingredients.take(MaxCraftingIngredients).forEach(::add) })
        target.put("result", contents.result)
    }
}

/** Furnace, blast furnace, smoker and campfire: one input, and a result that has no count. */
internal object CookingShape : RecipeShape {
    override fun read(json: JsonObject) = RecipeContents(listOfNotNull(json.get("ingredient")), json.get("result"))

    override fun write(target: JsonObject, contents: RecipeContents, previous: JsonObject) {
        target.copyShared(previous, CookingCategories)
        target.put("ingredient", contents.ingredients.firstOrNull())
        target.put("result", contents.result?.let { RecipeResult(it).withCount(1).json })
        target.copy(previous, "experience")
        target.copy(previous, "cookingtime")
    }
}

internal object StonecuttingShape : RecipeShape {
    override fun read(json: JsonObject) = RecipeContents(listOfNotNull(json.get("ingredient")), json.get("result"))

    override fun write(target: JsonObject, contents: RecipeContents, previous: JsonObject) {
        target.copy(previous, "group")
        target.put("ingredient", contents.ingredients.firstOrNull())
        target.put("result", contents.result)
    }
}

/** Template, base and addition in that order; a trim has no result of its own. */
internal class SmithingShape(private val hasResult: Boolean) : RecipeShape {
    override fun read(json: JsonObject) = RecipeContents(
        SmithingSlots.mapNotNull { json.get(it) },
        json.get("result").takeIf { hasResult },
    )

    override fun write(target: JsonObject, contents: RecipeContents, previous: JsonObject) {
        SmithingSlots.forEachIndexed { index, key -> target.put(key, contents.ingredients.getOrNull(index)) }
        if (hasResult) target.put("result", contents.result)
    }
}

internal val SmithingSlots = listOf("template", "base", "addition")

internal fun shapelessIngredients(json: JsonObject): List<JsonElement> =
    json.get("ingredients")?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()

private fun JsonObject.copyShared(previous: JsonObject, categories: List<String>) {
    copy(previous, "group")
    previous.primitive("category")?.asString?.takeIf { it in categories }?.let { addProperty("category", it) }
}

private fun JsonObject.copy(previous: JsonObject, key: String) {
    previous.get(key)?.let { add(key, it.deepCopy()) }
}
