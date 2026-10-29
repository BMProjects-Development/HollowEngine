package ru.hollowhorizon.hollowengine.client.ui.inspector

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import ru.hollowhorizon.hollowengine.client.utils.IconHelper
import ru.hollowhorizon.hollowengine.client.vfx.VfxAssets
import ru.hollowhorizon.hollowengine.common.decals.DecalSpec
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource
import ru.hollowhorizon.hollowengine.common.vfx.VfxFormat


private const val AssetCompletionLimit = 60

/** Opens host's asset picker for an `@EditorAsset` field; absent when the host has no picker. */
@Composable
internal fun AssetPickerButton(extensions: List<String>, current: String, label: String, onPick: (String) -> Unit) {
    AssetPickerButton(label, current, { host -> host.assets(extensions) }, onPick)
}

/** The same, for a field whose candidates are not plain files, such as shader names. */
@Composable
fun AssetPickerButton(
    label: String,
    current: String,
    candidates: (InspectorHost) -> List<String>,
    onPick: (String) -> Unit,
) {
    val host = LocalInspectorHost.current ?: return
    if (!host.canPickAssets) return

    InspectorIconButton(
        InspectorIcons.FOLDER,
        InspectorLang.pick,
        tags = listOf("insp-pick-button"),
    ) {
        host.pickAsset(InspectorAssetRequest(label, candidates(host), current, onPick))
    }
}

fun assetCompletions(candidates: List<String>): UiCompletionContributor = UiCompletionContributor { context ->
    val prefix = context.text.take(context.caret).trim()
    candidates.asSequence().filter { prefix.isBlank() || it.contains(prefix, ignoreCase = true) }
        .take(AssetCompletionLimit).map { candidate ->
            UiTextCompletion(
                label = candidate,
                insertText = candidate,
                icon = IconHelper.forPath(candidate).toString(),
                wordChars = ":/._-",
            )
        }.toList()
}

/**
 * An underline under [value] when [exists] says there is nothing there, with a fix for every
 * candidate that ends the same way: a texture moved to another folder or namespace is the usual case.
 */
fun assetDiagnostics(value: String, candidates: List<String>, exists: (String) -> Boolean): List<UiTextDiagnostic> {
    val path = value.trim()
    if (path.isEmpty() || exists(path)) return emptyList()

    val name = path.substringAfterLast('/').substringAfterLast(':')
    val fixes = candidates.asSequence()
        .filter { it != path && it.substringAfterLast('/').substringAfterLast(':') == name }
        .take(MaxAssetFixes)
        .map { UiTextQuickFix(InspectorLang.useAsset(it), listOf(UiTextEdit(0, value.length, it))) }
        .toList()
    return listOf(
        UiTextDiagnostic(
            start = 0,
            end = value.length,
            message = InspectorLang.assetMissing(path),
            severity = UiTextDiagnosticSeverity.ERROR,
            fixes = fixes,
        )
    )
}

private const val MaxAssetFixes = 5

/** Whether the resource pack stack has a file at [path], a `namespace:path` location. */
fun resourceExists(path: String): Boolean {
    val location = ResourceLocation.tryParse(path) ?: return false
    val manager = Minecraft.getInstance().resourceManager ?: return true
    return manager.getResource(location).isPresent
}

/**
 * A resource path typed with completions, checked against the loaded packs, and picked from a tree.
 *
 * [candidates] lists what can be picked; [exists] decides whether what is typed is there. Both are
 * given so a field can name something other than a file, such as a shader by its short name.
 */
@Composable
fun AssetPathField(
    value: String,
    label: String,
    id: String,
    candidates: (InspectorHost) -> List<String>,
    exists: (String) -> Boolean = ::resourceExists,
    placeholder: String = "",
    onChange: (String) -> Unit,
) {
    val host = LocalInspectorHost.current
    val listed = remember(host) { host?.let(candidates).orEmpty() }
    val diagnostics = remember(value, listed) { assetDiagnostics(value, listed, exists) }

    Row(tags = listOf("insp-input-row")) {
        TextField(
            value = value,
            id = id,
            fontSize = 9f,
            placeholder = placeholder,
            insight = UiCodeInsight(
                completions = if (listed.isEmpty()) null else assetCompletions(listed),
                diagnostics = diagnostics,
            ),
            onChange = onChange,
            tags = listOf("insp-input", "insp-inline-input"),
            modifier = Modifier.size(0.px, UiLength.Fit).grow(1f),
        )
        AssetPickerButton(label, value, candidates, onChange)
    }
}

/**
 * Where an `@EditorAsset` field's suggestions come from.
 */
object EditorAssetSources {
    private val providers = LinkedHashMap<String, () -> List<String>>()

    init {
        register(*HollowModelManager.allSupportedFormats.toTypedArray()) { HollowModelManager.allModels.map { it.toString() } }
        register(VfxFormat.EXTENSION) { VfxAssets.ids.map { it.toString() } }
    }

    fun register(vararg extensions: String, provider: () -> List<String>) {
        extensions.forEach {
            providers[it] = provider
        }
    }

    fun list(extension: String): List<String> =
        providers[extension]?.invoke()?.sorted() ?: resourcesEndingWith(extension)

    private fun resourcesEndingWith(extension: String): List<String> {
        val manager = Minecraft.getInstance().resourceManager ?: return emptyList()
        val root = when {
            extension.endsWith("png") -> "textures"
            extension.endsWith("ogg") || extension.endsWith("wav") -> "sounds"
            extension.removePrefix(".") in SoundSource.fileExtensions -> "sounds"
            extension in DecalSpec.VideoExtensions -> "videos"
            else -> "models"
        }
        return runCatching {
            manager.listResources(root) { it.path.endsWith(extension) }.keys.map { it.toString() }.sorted()
        }.getOrDefault(emptyList())
    }
}

private val CenteredOnViewport = UiPopupAlignment(
    anchorHorizontal = UiAlign.CENTER,
    anchorVertical = UiAlign.CENTER,
    popupHorizontal = UiAlign.CENTER,
    popupVertical = UiAlign.CENTER,
)

/** A modal window in the middle of the screen, in the inspector's look. */
@Composable
fun InspectorDialog(id: String, title: String, width: Float, onClose: () -> Unit, content: HollowUiContent) {
    val viewport = LocalUiViewport.current
    Popup(
        anchorBounds = viewport,
        alignment = CenteredOnViewport,
        id = "$id-popup",
        layer = 100,
        modal = true,
        onDismiss = onClose,
    ) {
        Column(
            id = id,
            tags = listOf("insp-dialog"),
            modifier = Modifier.style(InspectorStylesheet).size(width.px, UiLength.Fit)
                .maxSize(height = (viewport.height - 80f).coerceAtLeast(160f).px),
        ) {
            Row(tags = listOf("insp-dialog-head")) {
                Text(title, tags = listOf("insp-dialog-title"), modifier = Modifier.grow(1f))
                InspectorIconButton(InspectorIcons.CLOSE, InspectorLang.close) { onClose() }
            }
            content()
        }
    }
}

/** The tree of everything [request] offers, filtered as the user types. */
@Composable
fun AssetPickerDialog(request: InspectorAssetRequest, onClose: () -> Unit) {
    var filter by remember(request) { mutableStateOf("") }
    val expanded = remember(request) { mutableStateSetOf<String>() }

    val rows = AssetPathTree.rows(
        paths = request.candidates,
        expanded = expanded,
        query = filter,
        folderIcon = InspectorIcons.FOLDER,
        fileIcon = { IconHelper.forPath(it).toString() },
        selected = request.current,
    )

    InspectorDialog("insp-asset-dialog", request.title, 420f, onClose) {
        Row(tags = listOf("insp-search")) {
            Image(InspectorIcons.SEARCH, tags = listOf("insp-search-icon"))
            TextField(
                value = filter,
                id = "insp-asset-filter",
                placeholder = InspectorLang.search,
                fontSize = 9f,
                onChange = { filter = it },
                tags = listOf("insp-input", "flat"),
                modifier = Modifier.size(0.px, UiLength.Fit).grow(1f),
            )
        }

        if (rows.isEmpty()) {
            Text(InspectorLang.nothingFound, tags = listOf("insp-hint"))
            return@InspectorDialog
        }

        UiTreeView(
            items = rows,
            onToggle = { item -> if (!expanded.add(item.id)) expanded.remove(item.id) },
            onSelect = { item, _ ->
                val picked = item.payload
                if (picked == null) {
                    if (!expanded.add(item.id)) expanded.remove(item.id)
                } else {
                    request.onPick(picked)
                    onClose()
                }
            },
            modifier = Modifier.size(100.percent, 280.px),
            tags = listOf("insp-asset-tree"),
        )
    }
}
