package ru.hollowhorizon.hollowengine.client.ui.ide.recipe

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

internal const val GridSize = 3

/** Keys a new ingredient of a shaped recipe gets, in order; ones the file already uses are kept. */
private const val Symbols = "#XIABCDEFGHJKLMNOPQRSTUVWYZ0123456789"

internal fun shapedCells(json: JsonObject): List<JsonElement?>? {
    val pattern = json.get("pattern")?.takeIf { it.isJsonArray }?.asJsonArray?.strings().orEmpty()
    if (pattern.size > GridSize || pattern.any { it.length > GridSize }) return null
    val keys = json.get("key")?.takeIf { it.isJsonObject }?.asJsonObject
    return List(GridSize * GridSize) { index ->
        val symbol = pattern.getOrNull(index / GridSize)?.getOrNull(index % GridSize)
        if (symbol == null || symbol == ' ') null else keys?.get(symbol.toString())
    }
}

/**
 * Writes [cells] back as the smallest pattern holding them, which is how vanilla matches it anyway.
 * An ingredient keeps the key it had, so a hand-written `#` stays a `#`.
 */
internal fun JsonObject.writeShaped(cells: List<JsonElement?>) {
    val previous = get("key")?.takeIf { it.isJsonObject }?.asJsonObject
    val filled = cells.indices.filter { cells[it] != null }
    val symbols = LinkedHashMap<JsonElement, Char>()
    val kept = previous?.entrySet().orEmpty().filter { (key, value) -> key.length == 1 && value in cells }
        .associate { (key, value) -> value to key[0] }

    // A new ingredient must not take a key that one still in the grid is about to keep.
    fun symbolOf(ingredient: JsonElement): Char = symbols.getOrPut(ingredient) {
        kept[ingredient] ?: Symbols.first { it !in symbols.values && it !in kept.values }
    }

    val pattern = JsonArray()
    if (filled.isNotEmpty()) {
        val rows = filled.minOf { it / GridSize }..filled.maxOf { it / GridSize }
        val columns = filled.minOf { it % GridSize }..filled.maxOf { it % GridSize }
        for (row in rows) {
            pattern.add(buildString {
                for (column in columns) append(cells[row * GridSize + column]?.let(::symbolOf) ?: ' ')
            })
        }
    }
    add("pattern", pattern)
    add("key", JsonObject().apply { symbols.forEach { (ingredient, symbol) -> add(symbol.toString(), ingredient) } })
}
