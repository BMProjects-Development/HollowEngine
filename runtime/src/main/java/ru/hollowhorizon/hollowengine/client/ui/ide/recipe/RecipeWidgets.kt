package ru.hollowhorizon.hollowengine.client.ui.ide.recipe

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.google.gson.JsonPrimitive
import net.minecraft.locale.Language
import net.minecraft.world.item.ItemStack
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.itemTooltip
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang

private const val RecipeIcons = "hollowengine:textures/gui/icons/recipe"
private const val MaxCount = 99

/** Ticks once a second; slots showing a tag or several alternatives step through their items on it. */
val LocalRecipeCycle = compositionLocalOf { 0 }

/** Slots and arrows in one centered row, the way recipe viewers lay a recipe out. */
@Composable
fun RecipeLayout(content: HollowUiContent) {
    Row(tags = listOf("recipe-layout"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER), content = content)
}

/** [rows] x [columns] slots; [slot] draws the one at a row and a column. */
@Composable
fun RecipeGrid(rows: Int, columns: Int, slot: @Composable (row: Int, column: Int) -> Unit) {
    Column(tags = listOf("recipe-grid")) {
        repeat(rows) { row ->
            Row(tags = listOf("recipe-grid-row")) {
                repeat(columns) { column -> slot(row, column) }
            }
        }
    }
}

@Composable
fun RecipeArrow() = Image("$RecipeIcons/arrow.svg", tags = listOf("recipe-arrow"))

@Composable
fun RecipePlus() = Image("$RecipeIcons/plus.svg", tags = listOf("recipe-plus"))

@Composable
fun RecipeFlame() = Image("$RecipeIcons/flame.svg", tags = listOf("recipe-flame"))

/** An ingredient slot for the top-level [key]. */
@Composable
fun RecipeEditing.IngredientSlot(key: String, id: String = key) =
    Slot(RecipeSlotBinding.key(key, RecipeSlotKind.INGREDIENT, id))

/** The output under [key], and its count when [count] is set; a result slot takes items only. */
@Composable
fun RecipeEditing.ResultSlot(key: String = "result", count: Boolean = true) {
    val result = result(key)
    Row(tags = listOf("recipe-result"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
        Slot(RecipeSlotBinding.key(key, RecipeSlotKind.RESULT, id = "result-$key", counted = count))
        if (count && result != null) {
            NumberRow(recipeLang("count").lang, result.count, id = "recipe-$key-count", min = 1, max = MaxCount) { next ->
                edit { RecipeResult.of(get(key))?.let { put(key, it.withCount(next).json) } }
            }
        }
    }
}

/**
 * A slot bound to any place in the recipe. A click picks it for the palette and the inspector, a
 * right click clears it, and an item dragged from the palette or the inventory drops into it.
 */
@Composable
fun RecipeEditing.Slot(binding: RecipeSlotBinding, large: Boolean = binding.kind == RecipeSlotKind.RESULT) {
    val value = binding.read(json)
    val stacks = when (binding.kind) {
        RecipeSlotKind.INGREDIENT -> RecipeIngredient.of(value)?.stacks.orEmpty()
        RecipeSlotKind.RESULT -> listOfNotNull(RecipeResult.of(value)?.stack?.takeUnless(ItemStack::isEmpty))
    }
    val unknown = value != null && stacks.isEmpty()
    val shown = stacks.takeIf { it.isNotEmpty() }?.let { it[LocalRecipeCycle.current % it.size] }
    val dropId = "recipe-drop-${binding.id}"
    val dragAndDrop = LocalDragAndDrop.current
    val dropping = dragAndDrop?.hoveredTargetId == dropId && dragAndDrop.canDrop

    val interactive = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND).dropTarget(
        id = dropId,
        accepts = { item ->
            val pick = item.payload as? RecipePick
            pick != null && !readOnly && (binding.kind == RecipeSlotKind.INGREDIENT || pick.tag == null)
        },
        onDrop = { item, _, _ ->
            session.apply(binding, item.payload as RecipePick)
            session.select(binding)
            true
        },
    ).onClick { event ->
        when {
            event.isLeftClick() -> session.select(binding)
            event.isRightClick() -> session.write(binding, null)
        }
        event.consume()
    }
    Box(
        id = "recipe-slot-${binding.id}",
        mode = UiBoxMode.STACK,
        tags = listOfNotNull(
            "recipe-slot",
            "large".takeIf { large },
            "selected".takeIf { session.selected?.id == binding.id },
            "unknown".takeIf { unknown },
            "drop".takeIf { dropping },
        ),
        modifier = if (shown != null) {
            interactive.itemTooltip { shown }
        } else {
            interactive.tooltipOnHover(RecipeIngredient.of(value)?.label.orEmpty())
        },
    ) {
        when {
            shown != null -> Item(
                shown,
                tags = listOf("recipe-slot-item"),
                modifier = Modifier.size(16.px, 16.px).align(UiAlign.CENTER, UiAlign.CENTER).inputTransparent(),
            )

            unknown -> Text(
                "?", tags = listOf("recipe-slot-unknown"), modifier = Modifier.align(UiAlign.CENTER, UiAlign.CENTER)
            )
        }
    }
}

/** A whole number under [key]; [default] is left out of the file, as vanilla does. */
@Composable
fun RecipeEditing.IntField(key: String, label: String, default: Int = 0, min: Int = 0, max: Int = Int.MAX_VALUE) =
    NumberRow(label.lang, int(key, default), id = "recipe-field-$key", min = min, max = max) { set(key, it, default) }

@Composable
fun RecipeEditing.FloatField(
    key: String,
    label: String,
    default: Float = 0f,
    min: Float = 0f,
    max: Float = Float.MAX_VALUE,
) = NumberRow(label.lang, float(key, default), id = "recipe-field-$key", min = min, max = max) { set(key, it, default) }

@Composable
fun RecipeEditing.StringField(key: String, label: String, default: String = "") =
    TextRow(label.lang, string(key, default), id = "recipe-field-$key") { set(key, it, default) }

@Composable
fun RecipeEditing.ToggleField(key: String, label: String, default: Boolean = false) =
    ToggleRow(label.lang, boolean(key, default), switch = true) { set(key, it, default) }

/**
 * One of [values] under [key], as pills; [default] is what a missing key means. A value is labelled
 * by `hollowengine.gui.recipe_editor.value.<value>` when that key exists, and by itself otherwise.
 */
@Composable
fun RecipeEditing.ChoiceField(key: String, label: String, values: List<String>, default: String) {
    Column(tags = listOf("insp-field")) {
        Label(label.lang)
        Pills(values, string(key, default), label = ::valueLabel) { value ->
            edit { put(key, JsonPrimitive(value)) }
        }
    }
}

private fun valueLabel(value: String): String {
    val key = recipeLang("value.$value")
    return if (Language.getInstance().has(key)) key.lang else value
}
