package ru.hollowhorizon.hollowengine.client.ui.ide.recipe

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewContext
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdePreviewError
import ru.hollowhorizon.hollowengine.client.ui.ide.preview.HollowIdeTextModel
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorStylesheet
import ru.hollowhorizon.hollowengine.client.ui.inspector.PublishInspector
import ru.hollowhorizon.hollowengine.client.ui.scroll.UiScrollHandle
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdown
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.lang
import kotlin.time.Duration.Companion.seconds

private val RecipePath = Regex("""(^|/)data/[^/]+/recipes?/.+\.json$""", RegexOption.IGNORE_CASE)

/** A data pack recipe, in the project or among the game's resources. */
internal fun String.isRecipeFile(): Boolean = RecipePath.containsMatchIn(this)

/** What the preview keeps while its tab is switched away. */
private class RecipePreviewState {
    val model = HollowIdeTextModel(::parseRecipeJson)
    val session = RecipeEditorSession()
    val scroll = UiScrollHandle()
}

/**
 * The recipe's visual editor, picked by its `type` from [RecipeEditors], over the item palette; the
 * picked slot opens in the IDE's inspector. Everything it changes goes into the file's text, which
 * stays the source of truth.
 */
@Composable
internal fun RecipePreview(context: HollowIdePreviewContext) {
    val state = context.state { RecipePreviewState() }
    val session = state.session
    val parsed = state.model.read(context.text)
    val json = parsed.getOrNull() ?: return HollowIdePreviewError(parsed.exceptionOrNull())
    val recipe = remember(json, context.readOnly) {
        RecipeEditing(
            json = json,
            readOnly = context.readOnly,
            session = session,
            latest = { runCatching { parseRecipeJson(context.text) }.getOrNull() },
            write = { next -> context.edit(next.toRecipeText()) },
        )
    }
    SideEffect { session.editing = recipe }
    RecipeEditors.revision
    val editor = RecipeEditors.of(recipe.type)

    PublishInspector(source = "recipe-${context.file.path}", key = session.selected?.id) {
        session.selected?.let { recipeSlotInspector(session, it) }
    }

    var cycle by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1.seconds)
            cycle++
        }
    }

    Column(
        tags = listOf("ide-file-panel", "recipe-editor"),
        modifier = Modifier.style(InspectorStylesheet).style(RecipeEditorStylesheet).focusScope(),
    ) {
        Row(tags = listOf("recipe-editor-header"), modifier = Modifier.alignItems(vertical = UiAlign.CENTER)) {
            TypeSwitch(recipe, session, editor)
            Row(modifier = Modifier.grow(1f)) {}
            Text(recipe.type, tags = listOf("recipe-editor-type"))
        }
        TypeChangeWarning(recipe, session, editor)
        Column(tags = listOf("recipe-editor-body"), modifier = Modifier.scrollable(horizontal = false, state = state.scroll)) {
            when {
                recipe.type.isEmpty() -> Text(recipeLang("no_type").lang, tags = listOf("recipe-note"))
                editor == null -> Text(recipeLang("no_editor").lang(recipe.type), tags = listOf("recipe-note"))
                else -> CompositionLocalProvider(LocalRecipeCycle provides cycle) {
                    editor.content(recipe)
                }
            }
        }
        if (editor != null) RecipePalette(session, context.readOnly)
    }
}

/** The recipe's type as a menu of every type there is an editor for. */
@Composable
private fun TypeSwitch(recipe: RecipeEditing, session: RecipeEditorSession, current: RecipeEditorType?) {
    var open by remember { mutableStateOf(false) }
    UiDropdown(
        id = "recipe-type",
        label = current?.title?.lang ?: recipeLang("title").lang,
        expanded = open,
        onExpandedChange = { open = it && !recipe.readOnly },
        items = RecipeEditors.point.extensions.map { editor ->
            UiDropdownItem(
                label = editor.title?.lang ?: editor.type.toString(),
                checked = editor === current,
                onClick = { if (editor !== current) session.pendingType = editor },
            )
        },
        tags = listOf("recipe-type"),
    )
}

/**
 * Asks before a change of type, since fields the new type has no place for are dropped. Undo in the
 * text brings them back, which the warning says too.
 */
@Composable
private fun TypeChangeWarning(recipe: RecipeEditing, session: RecipeEditorSession, current: RecipeEditorType?) {
    val target = session.pendingType ?: return
    val carries = current?.shape != null && target.shape != null
    Column(tags = listOf("recipe-warning")) {
        Text(
            recipeLang("type_change.title").lang(target.title?.lang ?: target.type.toString()),
            tags = listOf("recipe-warning-title"),
        )
        Text(
            recipeLang(if (carries) "type_change.convert" else "type_change.keep").lang,
            tags = listOf("recipe-warning-text"),
        )
        Row(tags = listOf("recipe-warning-actions")) {
            InspectorButton(recipeLang("type_change.confirm").lang, tags = listOf("primary")) {
                session.pendingType = null
                session.select(null)
                recipe.convertTo(target)
            }
            InspectorButton(recipeLang("type_change.cancel").lang) { session.pendingType = null }
        }
    }
}
