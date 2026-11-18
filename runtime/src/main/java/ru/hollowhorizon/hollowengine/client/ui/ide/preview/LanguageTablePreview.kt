package ru.hollowhorizon.hollowengine.client.ui.ide.preview

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.language.LanguageInfo
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOpenFile
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import ru.hollowhorizon.hollowengine.client.utils.lang

private const val Lang = "hollowengine.gui.ide.language_table"
private const val SaveIcon = "hollowengine:textures/gui/icons/save.svg"
private const val SearchIcon = "hollowengine:textures/gui/icons/search.svg"
private const val SyncIcon = "hollowengine:textures/gui/icons/vfx/restart.svg"

internal val languageTablePreview = HollowIdeFilePreview(
    id = "language-table",
    defaultMode = HollowIdeViewMode.PREVIEW,
    title = "$Lang.title",
    matcher = { path -> languageFileCode(path)?.let { Minecraft.getInstance()?.languageManager?.getLanguage(it) } != null },
    content = { LanguageTablePreview(it) },
)

@Composable
private fun LanguageTablePreview(context: HollowIdePreviewContext) {
    val primary = languageFileCode(context.file.projectPath) ?: return
    val languageManager = Minecraft.getInstance().languageManager
    val languages = languageManager.languages
    var selection by remember(context, primary) {
        mutableStateOf(LanguageTableSelection.initial(primary, languageManager.selected, languages.keys))
    }
    val sourceLanguage = selection.source
    val targetLanguage = selection.target

    fun documentFor(code: String): HollowIdeOpenFile? =
        if (code == primary) context.file else context.sibling("$code.json")

    val sourceDoc = documentFor(sourceLanguage)
    val targetDoc = targetLanguage?.let(::documentFor)
    val sourceModel = remember(context, sourceLanguage) { HollowIdeTextModel(LanguageCatalog::parse) }
    val targetModel = remember(context, targetLanguage) { HollowIdeTextModel(LanguageCatalog::parse) }
    val sourceCatalog = sourceDoc?.textOrNull?.let(sourceModel::read)?.getOrNull()
    val targetCatalog = targetDoc?.textOrNull?.let(targetModel::read)?.getOrNull()
    var query by remember(context) { mutableStateOf("") }
    var selectedKey by remember(context) { mutableStateOf<String?>(null) }

    val sourceKeys = sourceCatalog?.entries.orEmpty().keys
    val targetKeys = targetCatalog?.entries.orEmpty().keys
    val keys = remember(sourceKeys, targetKeys) { (sourceKeys + targetKeys).sorted() }
    val rows = remember(keys, sourceCatalog, targetCatalog, query) {
        val filter = query.trim()
        if (filter.isEmpty()) {
            keys
        } else {
            keys.filter { key ->
                key.contains(filter, ignoreCase = true) ||
                    sourceCatalog?.entries?.get(key)?.contains(filter, ignoreCase = true) == true ||
                    targetCatalog?.entries?.get(key)?.contains(filter, ignoreCase = true) == true
            }
        }
    }

    fun edit(key: String, value: String) {
        val language = targetLanguage ?: return
        val document = documentFor(language) ?: return
        if (!document.canEditLanguage) return
        val source = document.textOrNull ?: return
        val catalog = targetModel.read(source).getOrNull() ?: return
        if (catalog.entries[key] == value) return
        val next = catalog.withTranslation(key, value)
        val label = UndoLabel("$Lang.edit", key)
        val mergeKey = "language-table:$key"
        if (language == primary) context.edit(next, label, mergeKey)
        else context.editSibling("$language.json", next, label, mergeKey)
    }

    val id = context.file.id
    val documents = listOfNotNull(sourceDoc, targetDoc)
    val canEditTarget = targetDoc?.canEditLanguage == true && targetCatalog != null

    Column(tags = listOf("language-preview"), modifier = Modifier.size(100.percent, 100.percent)) {
        Row(
            tags = listOf("language-preview-toolbar"),
            modifier = Modifier.size(100.percent, UiLength.Fit),
        ) {
            Row(
                tags = listOf("language-preview-tools"),
                modifier = Modifier.size(0.px, UiLength.Fit).grow(1f),
            ) {
                LanguageSelector("$id-from-dropdown", "$Lang.from".lang, sourceLanguage, languages) {
                    selection = selection.selectSource(it)
                }
                LanguageSelector(
                    "$id-to-dropdown", "$Lang.to".lang, targetLanguage, languages,
                    disabledCode = sourceLanguage.takeIf { targetLanguage == null },
                ) {
                    selection = selection.selectTarget(it)
                }
                Row(
                    tags = listOf("language-preview-search"),
                    modifier = Modifier.size(0.px, 24.px).grow(1f).maxSize(164.px),
                ) {
                    Image(SearchIcon, tags = listOf("language-preview-search-icon"))
                    TextField(
                        id = "$id-language-filter",
                        value = query,
                        onChange = { query = it },
                        placeholder = "$Lang.search_placeholder".lang,
                        tags = listOf("language-preview-search-input"),
                        modifier = Modifier.size(0.px, 100.percent).grow(1f),
                    )
                }
            }

            IconButton(
                icon = SaveIcon,
                tooltip = "$Lang.save".lang,
                id = "$id-save-languages",
                enabled = documents.any { it.hasUnsavedChanges },
                tags = listOf("language-preview-save-btn"),
            ) {
                documents.filter { it.hasUnsavedChanges }.forEach { it.save() }
            }
        }

        LanguageDocumentStatus(context, sourceLanguage, sourceDoc, sourceCatalog)
        if (targetLanguage != null) {
            LanguageDocumentStatus(context, targetLanguage, targetDoc, targetCatalog)
        }

        Row(
            tags = listOf("language-preview-columns", "language-preview-header"),
            modifier = Modifier.size(100.percent, 24.px),
        ) {
            listOf("key", "original", "translation").forEach { header ->
                Text("$Lang.$header".lang, tags = listOf("language-preview-header-col"), modifier = tableColumn())
            }
        }

        if (rows.isEmpty()) {
            Row(
                tags = listOf("language-preview-status-bar"),
                modifier = Modifier.size(100.percent, UiLength.Fit).alignItems(vertical = UiAlign.CENTER),
            ) {
                Text("$Lang.empty".lang, tags = listOf("language-preview-status"))
                val trimmedQuery = query.trim()
                if (trimmedQuery.isNotEmpty() && canEditTarget) {
                    Button(
                        "$Lang.add".lang(trimmedQuery),
                        id = "$id-add-searched-key",
                    ) {
                        edit(trimmedQuery, "")
                        selectedKey = trimmedQuery
                    }
                }
            }
        }

        LazyColumn(
            id = "$id-language-rows",
            tags = listOf("language-preview-rows"),
            modifier = Modifier.size(100.percent, 0.px).grow(1f),
        ) {
            items(rows.size, key = { rows[it] }) { index ->
                val key = rows[index]
                val isSelected = selectedKey == key
                val originalValue = sourceCatalog?.entries?.get(key)
                val targetValue = targetCatalog?.entries?.get(key)

                Row(
                    id = "$id-row-$key",
                    tags = listOfNotNull(
                        "language-preview-row",
                        "language-preview-columns",
                        "alternate".takeIf { index % 2 != 0 },
                        "selected".takeIf { isSelected },
                    ),
                    modifier = Modifier.size(100.percent, 32.px)
                        .input(hoverable = true, clickable = true)
                        .onClick { event ->
                            if (event.button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return@onClick
                            selectedKey = key
                            event.consume()
                        },
                ) {
                    Text(
                        key,
                        id = "$id-key-$key",
                        tags = listOf("language-preview-key"),
                        modifier = tableColumn().tooltipOnHover(key),
                    )

                    Text(
                        originalValue.orEmpty(),
                        id = "$id-original-$key",
                        tags = listOf("language-preview-original"),
                        modifier = tableColumn().tooltipOnHover(originalValue.orEmpty()),
                    )

                    Row(
                        tags = listOf("language-preview-translation-cell"),
                        modifier = tableColumn(24.px),
                    ) {
                        key(targetLanguage) {
                            TextField(
                                id = "$id-$targetLanguage-$key",
                                value = targetValue.orEmpty(),
                                onChange = if (canEditTarget) ({ next -> edit(key, next) }) else null,
                                readOnly = !canEditTarget,
                                mode = UiTextFieldMode.MULTI_LINE,
                                placeholder = if (canEditTarget && targetValue == null) "$Lang.missing".lang else "",
                                tags = listOfNotNull(
                                    "language-preview-translation-input",
                                    "missing".takeIf { targetValue == null },
                                ),
                                modifier = Modifier.size(0.px, 100.percent).grow(1f).onFocus {
                                    selectedKey = key
                                },
                            )
                        }

                        IconButton(
                            icon = SyncIcon,
                            tooltip = "$Lang.copy_original".lang,
                            id = "$id-copy-$key",
                            enabled = canEditTarget && originalValue != null && originalValue != targetValue,
                            tags = listOf("language-preview-copy-btn"),
                            onClick = {
                                selectedKey = key
                                originalValue?.let { edit(key, it) }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun tableColumn(height: UiLength = UiLength.Fit): Modifier = Modifier.size(0.px, height).grow(1f)

@Composable
private fun LanguageSelector(
    id: String,
    label: String,
    language: String?,
    languages: Map<String, LanguageInfo>,
    disabledCode: String? = null,
    select: (String) -> Unit,
) {
    var expanded by remember(id) { mutableStateOf(false) }
    val displayName = language?.let { languages[it]?.toComponent()?.string ?: it } ?: "$Lang.choose_language".lang
    Row(tags = listOf("language-preview-selector-group")) {
        Text(label, tags = listOf("language-preview-label"))
        UiDropdown(
            id = id,
            label = displayName,
            expanded = expanded,
            onExpandedChange = { expanded = it },
            items = if (!expanded) emptyList() else languages.map { (code, info) ->
                UiDropdownItem(
                    "${info.toComponent().string} · $code",
                    mark = UiMenuParts.radio(code == language),
                    enabled = code != disabledCode,
                ) { select(code) }
            },
            tags = listOf("language-preview-dropdown"),
            search = "$Lang.search_languages".lang,
        )
    }
}

@Composable
private fun LanguageDocumentStatus(
    context: HollowIdePreviewContext,
    language: String,
    document: HollowIdeOpenFile?,
    catalog: LanguageCatalog?,
) {
    val filename = "$language.json"
    val canOpen = context.canOpenSiblings
    var loadState by remember(context, language, canOpen) { mutableStateOf(SiblingLoad.IDLE) }

    fun open() {
        loadState = SiblingLoad.LOADING
        context.openSibling(filename) { found ->
            loadState = if (found) SiblingLoad.IDLE else SiblingLoad.FAILED
        }
    }

    LaunchedEffect(context, language, canOpen) {
        if (document == null && canOpen) open()
    }

    if (document == null && canOpen) {
        Row(
            tags = listOf("language-preview-status-bar"),
            modifier = Modifier.size(100.percent, UiLength.Fit).alignItems(vertical = UiAlign.CENTER),
        ) {
            val status = when (loadState) {
                SiblingLoad.LOADING -> "loading"
                SiblingLoad.FAILED -> "unavailable"
                SiblingLoad.IDLE -> "closed"
            }
            Text(
                "$Lang.$status".lang(filename),
                tags = listOf("language-preview-status"),
                modifier = Modifier.size(0.px, UiLength.Fit).grow(1f).textWrap(),
            )
            if (loadState != SiblingLoad.LOADING) {
                Button("$Lang.open".lang, id = "${context.file.id}-open-$language", onClick = ::open)
            }
        }
    } else if (document != null && catalog == null) {
        Text(
            "$Lang.invalid".lang(filename),
            tags = listOf("language-preview-status", "language-preview-error"),
            modifier = Modifier.size(100.percent).textWrap(),
        )
    }
}

private val HollowIdeOpenFile.canEditLanguage: Boolean
    get() = !readOnly && remote?.missing != true

private enum class SiblingLoad { IDLE, LOADING, FAILED }
