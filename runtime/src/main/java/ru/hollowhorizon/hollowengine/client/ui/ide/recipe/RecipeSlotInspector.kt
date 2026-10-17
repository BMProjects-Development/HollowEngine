package ru.hollowhorizon.hollowengine.client.ui.ide.recipe

import androidx.compose.runtime.Composable
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import net.minecraft.core.registries.BuiltInRegistries
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContributor
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextCompletion
import ru.hollowhorizon.hollowengine.client.ui.widgets.itemTooltip
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonRuntimeEnvironment

private const val RemoveIcon = "hollowengine:textures/gui/icons/remove.svg"
private const val MaxCompletions = 60
private const val MaxCount = 99

/**
 * The selected slot in the IDE's inspector: every alternative of an ingredient, its tags and
 * components, or the result's id, count and components. It reads the recipe through [session], so
 * it follows edits made in the text or anywhere else without being published again.
 */
internal fun recipeSlotInspector(session: RecipeEditorSession, binding: RecipeSlotBinding) = InspectorTarget(
    id = "recipe-slot-${binding.id}",
    title = recipeLang(if (binding.kind == RecipeSlotKind.RESULT) "inspector.result" else "inspector.ingredient").lang,
    subtitle = binding.id,
    styles = listOf(RecipeEditorStylesheet),
    content = { SlotInspector(session, binding) },
)

internal const val RecipeEditorStylesheet = "hollowengine:ui/styles/recipe-editor.hss"

@Composable
private fun SlotInspector(session: RecipeEditorSession, binding: RecipeSlotBinding) {
    val recipe = session.editing ?: return
    val value = binding.read(recipe.json)
    when (binding.kind) {
        RecipeSlotKind.INGREDIENT -> IngredientInspector(session, binding, RecipeIngredient.of(value))
        RecipeSlotKind.RESULT -> ResultInspector(session, binding, RecipeResult.of(value))
    }
    if (value != null) {
        InspectorButton(recipeLang("inspector.clear").lang, RemoveIcon) { session.write(binding, null) }
    }
}

@Composable
private fun IngredientInspector(session: RecipeEditorSession, binding: RecipeSlotBinding, ingredient: RecipeIngredient?) {
    if (ingredient == null) {
        Hint(recipeLang("inspector.empty").lang)
        return
    }
    val alternatives = ingredient.alternatives
    val json = ingredient.json
    when {
        alternatives != null -> Section(recipeLang("inspector.alternatives").lang(alternatives.size)) {
            alternatives.forEachIndexed { index, alternative ->
                AlternativeRow(index, alternative) { next ->
                    val rest = alternatives.toMutableList()
                    if (next == null) rest.removeAt(index) else rest[index] = next
                    session.write(binding, RecipeIngredient.of(rest)?.json)
                }
            }
            Hint(recipeLang("inspector.alternatives_hint").lang)
        }

        json.isJsonObject && RecipeComponents.isComponents(json.asJsonObject) ->
            ComponentsIngredient(session, binding, json.asJsonObject)

        else -> Section(recipeLang("inspector.custom").lang) {
            Text(json.toPrettyJson(), tags = listOf("recipe-json"))
            Hint(recipeLang("inspector.custom_hint").lang)
        }
    }
}

/** One item or tag; [onChange] with null removes it. */
@Composable
private fun AlternativeRow(index: Int, alternative: JsonObject, onChange: (JsonObject?) -> Unit) {
    val tag = alternative.has("tag")
    val id = (alternative.primitive("tag") ?: alternative.primitive("item"))?.asString.orEmpty()
    val shown = RecipeIngredient(alternative).stacks.firstOrNull()
    Row(tags = listOf("recipe-alternative"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
        Row(tags = listOf("recipe-alternative-icon"), modifier = Modifier.itemTooltip { shown }) {
            shown?.let { Item(it, modifier = Modifier.size(16.px, 16.px).inputTransparent()) }
        }
        Pills(listOf(false, true), tag, label = { recipeLang(if (it) "inspector.tag" else "inspector.item").lang }) { toTag ->
            onChange(JsonObject().apply { addProperty(if (toTag) "tag" else "item", id) })
        }
        Row(modifier = Modifier.grow(1f)) {
            TextRow("", id, id = "recipe-alternative-$index", completions = idCompletions(tag)) { text ->
                onChange(JsonObject().apply { addProperty(if (tag) "tag" else "item", text.trim().removePrefix("#")) })
            }
        }
        InspectorIconButton(RemoveIcon, recipeLang("inspector.remove").lang) { onChange(null) }
    }
}

@Composable
private fun ComponentsIngredient(session: RecipeEditorSession, binding: RecipeSlotBinding, json: JsonObject) {
    Section(recipeLang("inspector.components").lang) {
        RecipeComponents.base(json)?.let { base ->
            Readonly(recipeLang("inspector.items").lang, RecipeIngredient(base).label)
        }
        RecipeComponents.components(json)?.let { Text(it.toPrettyJson(), tags = listOf("recipe-json")) }
        if (HollowAddonRuntimeEnvironment.platform == RuntimePlatform.NEOFORGE) {
            ToggleRow(
                recipeLang("inspector.strict").lang,
                json.primitive("strict")?.asBoolean ?: false,
                switch = true,
                hint = recipeLang("inspector.strict_hint").lang,
            ) { strict ->
                session.write(binding, json.deepCopy().apply { put("strict", JsonPrimitive(true).takeIf { strict }) })
            }
        }
        InspectorButton(recipeLang("inspector.drop_components").lang) {
            session.write(binding, withoutComponents(json))
        }
    }
}

/** The same items as a plain ingredient: NeoForge names them as a holder set, Fabric as an ingredient. */
private fun withoutComponents(json: JsonObject): JsonElement? {
    val base = RecipeComponents.base(json) ?: return null
    if (!base.isJsonPrimitive) return base
    val id = base.asString
    return JsonObject().apply { if (id.startsWith("#")) addProperty("tag", id.removePrefix("#")) else addProperty("item", id) }
}

@Composable
private fun ResultInspector(session: RecipeEditorSession, binding: RecipeSlotBinding, result: RecipeResult?) {
    if (result == null) {
        Hint(recipeLang("inspector.empty").lang)
        return
    }
    TextRow(recipeLang("inspector.item").lang, result.id, id = "recipe-result-id", completions = idCompletions(tag = false)) { text ->
        session.write(binding, result.withId(text.trim(), keepComponents = true).json)
    }
    if (binding.counted) {
        NumberRow(recipeLang("count").lang, result.count, id = "recipe-result-count", min = 1, max = MaxCount) { count ->
            session.write(binding, result.withCount(count).json)
        }
    }
    result.components?.let { components ->
        Section(recipeLang("inspector.components").lang) {
            Text(components.toPrettyJson(), tags = listOf("recipe-json"))
            InspectorButton(recipeLang("inspector.drop_components").lang) {
                session.write(binding, result.withComponents(null).json)
            }
        }
    }
}

/** Item ids, or with [tag], item tag ids, starting with what is typed. */
private fun idCompletions(tag: Boolean) = UiCompletionContributor { context ->
    val prefix = context.text.take(context.caret.coerceIn(0, context.text.length)).removePrefix("#").lowercase()
    val ids = if (tag) {
        BuiltInRegistries.ITEM.getTagNames().map { it.location().toString() }.toList()
    } else {
        ItemIds
    }
    ids.asSequence()
        .filter { prefix.isEmpty() || it.startsWith(prefix) || it.substringAfter(':').startsWith(prefix) }
        .take(MaxCompletions)
        .map { UiTextCompletion(label = it, insertText = it, itemIcon = it.takeUnless { tag }) }
        .toList()
}

private val ItemIds: List<String> by lazy { BuiltInRegistries.ITEM.keySet().map { it.toString() }.sorted() }
