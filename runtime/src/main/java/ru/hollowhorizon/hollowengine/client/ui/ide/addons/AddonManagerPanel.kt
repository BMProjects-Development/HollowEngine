package ru.hollowhorizon.hollowengine.client.ui.ide.addons

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.UiTextOverflow
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonManager
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonState
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonStatus

internal const val AddonManagerLang = "hollowengine.gui.ide.addons"
internal const val AddonManagerIcon = "hollowengine:textures/gui/icons/addons/installed.svg"

private const val Stylesheet = "hollowengine:ui/styles/addon-manager.hss"
private const val SearchIcon = "hollowengine:textures/gui/icons/addons/search.svg"
private const val SearchInputId = "addon-manager-search"

private const val MinCardWidth = 190f
private const val CardGap = 10f
private const val SkeletonCount = 3
private const val SweepStep = 0.02f
private const val SweepStepMillis = 40L

private object AddonListCache {
    var statuses: List<HollowAddonStatus>? = null
}

private class AddonToggle(val id: String, val enable: Boolean)

@Composable
internal fun AddonManagerPanel(onFocusSearch: (String) -> Unit) {
    var installed by remember { mutableStateOf(AddonListCache.statuses) }
    var query by remember { mutableStateOf("") }
    var reloadRevision by remember { mutableStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }
    val pending = remember { mutableStateMapOf<String, Boolean>() }
    val toggles = remember { Channel<AddonToggle>(Channel.UNLIMITED) }

    LaunchedEffect(reloadRevision) {
        val statuses = HollowAddonManager.statuses.distinctBy { it.descriptor.id }
        installed = statuses
        AddonListCache.statuses = statuses
    }

    LaunchedEffect(Unit) {
        for (request in toggles) {
            val result = withContext(Dispatchers.IO) {
                if (request.enable) HollowAddonManager.enable(request.id) else HollowAddonManager.disable(request.id)
            }
            pending.remove(request.id)
            failure = if (result.successful) null else result.message
            reloadRevision++
        }
    }

    val onToggle: (HollowAddonStatus) -> Unit = { status ->
        val id = status.descriptor.id
        if (id !in pending) {
            pending[id] = !status.isOn
            toggles.trySend(AddonToggle(id, enable = !status.isOn))
        }
    }

    Column(
        tags = listOf("ide-panel", "addon-manager"),
        modifier = Modifier.style(Stylesheet).size(100.percent, 100.percent).scrollable(horizontal = false),
    ) {
        AddonSearch(query, onChange = { query = it }, onFocus = onFocusSearch)
        AddonHeader(installed?.size)
        AddonProgress(busy = pending.isNotEmpty())
        failure?.let { message ->
            AddonMessage(message, tags = listOf("addon-failure"), onClick = { failure = null })
        }
        val addons = installed
        when {
            addons == null -> AddonSkeleton()
            addons.isEmpty() -> AddonMessage("$AddonManagerLang.empty".lang)
            else -> {
                val shown = addons.filter { it.matches(query.trim()) }
                if (shown.isEmpty()) AddonMessage("$AddonManagerLang.nothing_found".lang)
                else AddonGrid(shown, pending, onToggle)
            }
        }
        Box(modifier = Modifier.size(100.percent, 1.px))
    }
}

@Composable
private fun AddonHeader(count: Int?) {
    Row(tags = listOf("addon-section-header")) {
        Image(AddonManagerIcon, tags = listOf("addon-section-icon"))
        Text("$AddonManagerLang.installed".lang, tags = listOf("addon-section-title"))
        Box(modifier = Modifier.size(0.px, 1.px).grow(1f))
        if (count != null) Text(count.toString(), tags = listOf("addon-section-count"))
    }
}

@Composable
private fun AddonProgress(busy: Boolean) {
    Box(tags = listOf("addon-progress"), modifier = Modifier.size(100.percent, 2.px).clip()) {
        if (busy) AddonSweep()
    }
}

@Composable
private fun AddonSweep() {
    var phase by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(timeMillis = SweepStepMillis)
            phase = (phase + SweepStep) % 1f
        }
    }
    Layout(
        content = { Box(tags = listOf("addon-progress-bar")) },
        modifier = Modifier.size(100.percent, 2.px),
        measurePolicy = addonSweepPolicy(phase),
    )
}

private fun addonSweepPolicy(phase: Float) = UiMeasurePolicy { measurables, constraints ->
    val width = constraints.maxWidth.takeIf { it.isFinite() } ?: 0f
    val bar = measurables.firstOrNull()?.measure(UiConstraints())
    layout(width, bar?.height ?: 0f) {
        bar?.place((width + bar.width) * phase - bar.width, 0f)
    }
}

@Composable
private fun AddonSkeleton() {
    Column(tags = listOf("addon-skeleton-list")) {
        repeat(SkeletonCount) {
            Column(tags = listOf("addon-skeleton")) {
                Box(tags = listOf("addon-skeleton-line", "wide"))
                Box(tags = listOf("addon-skeleton-line"))
            }
        }
    }
}

@Composable
private fun AddonSearch(query: String, onChange: (String) -> Unit, onFocus: (String) -> Unit) {
    Row(
        tags = listOf("addon-search"),
        modifier = Modifier.cursor(UiCursorShape.TEXT).onClick { event ->
            onFocus(SearchInputId)
            event.consume()
        },
    ) {
        Image(SearchIcon, tags = listOf("addon-search-icon"))
        TextField(
            value = query,
            placeholder = "$AddonManagerLang.search".lang,
            onChange = onChange,
            id = SearchInputId,
            tags = listOf("addon-search-input"),
            modifier = Modifier.grow(1f),
        )
    }
}

@Composable
private fun AddonMessage(text: String, tags: List<String> = emptyList(), onClick: (() -> Unit)? = null) {
    Text(
        text,
        tags = listOf("addon-message") + tags,
        modifier = onClick?.let { click ->
            Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND).onClick { event ->
                if (event.isLeftClick()) click()
                event.consume()
            }
        },
    )
}

@Composable
private fun AddonGrid(
    addons: List<HollowAddonStatus>,
    pending: Map<String, Boolean>,
    onToggle: (HollowAddonStatus) -> Unit,
) {
    Layout(
        content = {
            addons.forEach { status ->
                key(status.descriptor.id) {
                    AddonCard(status, pending[status.descriptor.id]) { onToggle(status) }
                }
            }
        },
        tags = listOf("addon-grid"),
        modifier = Modifier.size(100.percent, UiLength.Fit),
        measurePolicy = AddonGridMeasurePolicy,
    )
}

internal val AddonGridMeasurePolicy = UiMeasurePolicy { measurables, constraints ->
    val width = constraints.maxWidth.takeIf { it.isFinite() } ?: (MinCardWidth * 2 + CardGap)
    val columns = ((width + CardGap) / (MinCardWidth + CardGap)).toInt().coerceAtLeast(1)
    val cardWidth = ((width - CardGap * (columns - 1)) / columns).coerceAtLeast(0f)
    val rows = measurables.chunked(columns).map { row ->
        val measured = row.map { it.measure(UiConstraints(cardWidth, cardWidth)) }
        val tallest = measured.maxOf { it.height }
        if (measured.all { it.height == tallest }) measured
        else row.map { it.measure(UiConstraints(cardWidth, cardWidth, tallest, tallest)) }
    }
    val height = rows.sumOf { row -> row.maxOf { it.height }.toDouble() }.toFloat() +
        CardGap * (rows.size - 1).coerceAtLeast(0)
    layout(width, height) {
        var y = 0f
        rows.forEach { row ->
            row.forEachIndexed { column, placeable -> placeable.place(column * (cardWidth + CardGap), y) }
            y += row.maxOf { it.height } + CardGap
        }
    }
}

@Composable
private fun AddonCard(status: HollowAddonStatus, target: Boolean?, onToggle: () -> Unit) {
    val descriptor = status.descriptor
    val kind = AddonKind.of(descriptor.category)
    val shownOn = target ?: status.isOn
    Column(
        tags = listOfNotNull(
            "addon-card",
            kind.tag,
            "enabled".takeIf { shownOn },
            "pending".takeIf { target != null },
        ),
        modifier = Modifier.tooltipOnHover(status.tooltip()),
    ) {
        Row(tags = listOf("addon-card-header")) {
            Box(tags = listOf("addon-icon-tile", kind.tag)) {
                Image(kind.icon, tags = listOf("addon-icon"))
            }
            Column(tags = listOf("addon-heading")) {
                Text(
                    descriptor.name,
                    tags = listOf("addon-name"),
                    modifier = Modifier.textWrap(false).textOverflow(UiTextOverflow.DOTS),
                )
                Row(tags = listOf("addon-meta")) {
                    descriptor.category?.let { Text(it, tags = listOf("addon-category")) }
                    descriptor.authors.takeIf { it.isNotEmpty() }?.let {
                        Text(
                            it.joinToString(", "),
                            tags = listOf("addon-author"),
                            modifier = Modifier.textWrap(false).textOverflow(UiTextOverflow.DOTS),
                        )
                    }
                }
            }
            AddonSwitch(on = shownOn, pending = target != null, onToggle = onToggle)
        }
        Column(tags = listOf("addon-card-footer")) {
            Text(
                descriptor.description ?: "$AddonManagerLang.no_description".lang,
                tags = listOf("addon-description"),
            )
            if (target != null) {
                val label = if (target) "$AddonManagerLang.enabling" else "$AddonManagerLang.disabling"
                Text(label.lang, tags = listOf("addon-pending"))
            } else {
                Text("v${descriptor.version}", tags = listOf("addon-version"))
            }
        }
    }
}

@Composable
private fun AddonSwitch(on: Boolean, pending: Boolean, onToggle: () -> Unit) {
    Row(
        tags = listOfNotNull("addon-switch", "on".takeIf { on }, "pending".takeIf { pending }),
        modifier = Modifier.input(hoverable = true, clickable = true).cursor(UiCursorShape.HAND).onClick { event ->
            if (event.isLeftClick()) onToggle()
            event.consume()
        },
    ) {
        Box(tags = listOf("addon-switch-knob"))
    }
}

private val HollowAddonStatus.isOn: Boolean get() = state != HollowAddonState.DISABLED

private fun HollowAddonStatus.matches(query: String): Boolean {
    if (query.isEmpty()) return true
    val descriptor = descriptor
    return listOfNotNull(descriptor.name, descriptor.id, descriptor.description, descriptor.category)
        .plus(descriptor.authors)
        .any { it.contains(query, ignoreCase = true) }
}

private fun HollowAddonStatus.tooltip(): String = buildString {
    append(descriptor.id).append(" · ").append(fileName)
    append('\n').append("$AddonManagerLang.state.${state.name.lowercase()}".lang)
    details?.let { append('\n').append(it) }
}

private enum class AddonKind(val tag: String, val icon: String) {
    PERFORMANCE("performance", "hollowengine:textures/gui/icons/addons/performance.svg"),
    VISUAL("visual", "hollowengine:textures/gui/icons/addons/effects.svg"),
    LIBRARY("library", "hollowengine:textures/gui/icons/addons/library.svg"),
    INSTRUMENT("instrument", "hollowengine:textures/gui/icons/addons/layers.svg"),
    OTHER("other", "hollowengine:textures/gui/icons/addons/package.svg");

    companion object {
        fun of(category: String?): AddonKind {
            val name = category?.lowercase() ?: return OTHER
            return when {
                name.startsWith("perf") -> PERFORMANCE
                name.startsWith("visual") || name.startsWith("effect") -> VISUAL
                name.startsWith("lib") -> LIBRARY
                name.startsWith("instrument") || name.startsWith("tool") -> INSTRUMENT
                else -> OTHER
            }
        }
    }
}
