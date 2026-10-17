package ru.hollowhorizon.hollowengine.client.ui.screen

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.UiTextOverflow
import ru.hollowhorizon.hollowengine.client.ui.text.*
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdown
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownMark

/**
 * The text lab: every font source and every inline effect, each with its parameters editable live,
 * so a regression in glyph metrics, wrapping or an effect shows up here instead of in a screen
 * someone else is building.
 */
@Composable
fun TextLab() {
    var fontIndex by remember { mutableStateOf(0) }
    var fontMenuOpen by remember { mutableStateOf(false) }
    var fontSizeText by remember { mutableStateOf("16") }
    var wrap by remember { mutableStateOf(true) }
    var align by remember { mutableStateOf(UiTextAlign.LEFT) }
    var overflow by remember { mutableStateOf(UiTextOverflow.SHOW) }
    var stageWidth by remember { mutableStateOf(360f) }
    var stageHeight by remember { mutableStateOf(120f) }
    var sample by remember { mutableStateOf(DefaultSample) }
    val enabled = remember { mutableStateMapOf<String, Boolean>() }
    val params = remember { mutableStateMapOf<String, String>() }

    val family = TextLabFonts[fontIndex.coerceIn(TextLabFonts.indices)].family
    val fontSize = fontSizeText.toFloatOrNull()?.coerceIn(4f, 96f) ?: 14f
    val activeEffects = TextLabEffects.filter { enabled[it.id] == true }.map { it.build(params) }

    Column(
        tags = listOf("panel", "textlab-panel"),
        modifier = Modifier.scrollable(),
    ) {
        Row(tags = listOf("textlab-controls")) {
            UiDropdown(
                id = "textlab-font",
                label = TextLabFonts[fontIndex.coerceIn(TextLabFonts.indices)].label,
                expanded = fontMenuOpen,
                onExpandedChange = { fontMenuOpen = it },
                items = TextLabFonts.mapIndexed { index, font ->
                    UiDropdownItem(
                        label = font.label,
                        mark = UiDropdownMark.RADIO,
                        checked = index == fontIndex,
                        onClick = { fontIndex = index },
                    )
                },
            )
            labField("size", fontSizeText, width = 34f) { fontSizeText = it }
            labToggle("wrap", wrap) { wrap = !wrap }
            labPill("align: ${align.name.lowercase()}") {
                align = UiTextAlign.entries[(align.ordinal + 1) % UiTextAlign.entries.size]
            }
            labPill("overflow: ${overflow.name.lowercase()}") {
                overflow = UiTextOverflow.entries[(overflow.ordinal + 1) % UiTextOverflow.entries.size]
            }
            labPill("clear effects") { enabled.clear() }
        }

        sectionTitle("1 · Каждый эффект по отдельности")
        Text(
            "Галочка включает эффект в общей сцене ниже; поля правят его параметры. " +
                    "Превью справа всегда показывает только этот эффект.",
            tags = listOf("textlab-hint"),
        )
        Column(tags = listOf("textlab-catalog")) {
            TextLabEffects.forEach { effect ->
                effectRow(effect, enabled[effect.id] == true, params, family, fontSize) {
                    enabled[effect.id] = enabled[effect.id] != true
                }
            }
        }

        sectionTitle("2 · Сцена: перенос, выравнивание, переполнение")
        Row(tags = listOf("textlab-controls")) {
            Text("текст сцены", tags = listOf("textlab-label"))
            TextField(
                value = sample,
                tags = listOf("textlab-sample-field"),
                onChange = { sample = it },
            )
        }
        Text(
            "тяни правый и нижний край · ${stageWidth.toInt()}×${stageHeight.toInt()}px · " +
                    "включено эффектов: ${activeEffects.size}",
            tags = listOf("textlab-hint"),
        )
        Row(tags = listOf("textlab-stage-row")) {
            Column {
                Box(
                    tags = listOf("textlab-stage"),
                    modifier = Modifier.size(stageWidth.px, stageHeight.px),
                ) {
                    var paragraph = Modifier
                        .size(100.percent, UiLength.Auto)
                        .textAlign(align)
                        .textOverflow(overflow)
                        .fontFamily(family)
                        .fontSize(fontSize)
                        .textEffects(*activeEffects.toTypedArray())
                    if (!wrap) paragraph = paragraph.textWrap(false)
                    Text(sample, tags = listOf("textlab-para"), modifier = paragraph)
                }
                Box(
                    tags = listOf("textlab-handle-h"),
                    modifier = Modifier.input(hoverable = true, draggable = true)
                        .cursor(UiCursorShape.RESIZE_VERTICAL)
                        .onDrag { stageHeight = (stageHeight + it.deltaY).coerceIn(40f, 600f) },
                )
            }
            Box(
                tags = listOf("textlab-handle"),
                modifier = Modifier.input(hoverable = true, draggable = true)
                    .cursor(UiCursorShape.RESIZE_HORIZONTAL)
                    .onDrag { stageWidth = (stageWidth + it.deltaX).coerceIn(80f, 900f) },
            )
        }

        sectionTitle("3 · Все шрифты на разных размерах")
        Text(
            "Ванильный шрифт задуман под кратные 8px: на них он попиксельно совпадает с ванилью, " +
                    "между ними размывается, как и в самой ваниле.",
            tags = listOf("textlab-hint"),
        )
        Column(tags = listOf("textlab-table")) {
            Row(tags = listOf("textlab-row", "textlab-head")) {
                Text("шрифт", tags = listOf("textlab-cell-name"))
                TextLabSizes.forEach { size -> Text("${size.toInt()}px", tags = listOf("textlab-cell")) }
            }
            TextLabFonts.forEach { font ->
                Row(tags = listOf("textlab-row")) {
                    Text(font.label, tags = listOf("textlab-cell-name"))
                    TextLabSizes.forEach { size ->
                        Text(
                            Pangram,
                            tags = listOf("textlab-cell"),
                            modifier = Modifier.fontFamily(font.family).fontSize(size),
                        )
                    }
                }
            }
        }

        sectionTitle("4 · Стыки: шрифт × комбинация эффектов")
        Text(
            "Здесь ловятся артефакты на пересечениях.",
            tags = listOf("textlab-hint"),
        )
        Column(tags = listOf("textlab-table")) {
            Row(tags = listOf("textlab-row", "textlab-head")) {
                Text("шрифт", tags = listOf("textlab-cell-name"))
                TextLabCombos.forEach { combo -> Text(combo.label, tags = listOf("textlab-cell")) }
            }
            TextLabFonts.forEach { font ->
                Row(tags = listOf("textlab-row")) {
                    Text(font.label, tags = listOf("textlab-cell-name"))
                    TextLabCombos.forEach { combo ->
                        Text(
                            "Съешь ещё Ajgw",
                            tags = listOf("textlab-cell"),
                            modifier = Modifier
                                .fontFamily(font.family)
                                .fontSize(fontSize)
                                .textEffects(*combo.effects.toTypedArray()),
                        )
                    }
                }
            }
        }

        sectionTitle("5 · Смешанный поток")
        Text(
            "Разные шрифты и размеры в одной строке: проверка базовой линии, высоты строки и того, " +
                    "что пробел между спанами считается по своему шрифту.",
            tags = listOf("textlab-hint"),
        )
        Text(tags = listOf("textlab-para", "textlab-mixed")) {
            TextLabFonts.forEach { font ->
                Span(
                    "${font.short} ",
                    modifier = Modifier.fontFamily(font.family).fontSize(fontSize),
                )
            }
            Span("| ")
            Span("8px vanilla ", modifier = Modifier.fontFamily("vanilla").fontSize(8f))
            Span("16px vanilla ", modifier = Modifier.fontFamily("vanilla").fontSize(16f))
            Span("| крупный ", modifier = Modifier.fontSize(fontSize * 2f))
            Span("мелкий ", modifier = Modifier.fontSize(fontSize * 0.6f))
            Span("жирный+косой", modifier = Modifier.textEffects(Bold(), Italic()))
        }
    }
}

@Composable
private fun sectionTitle(title: String) {
    Text(title, tags = listOf("card-title", "textlab-section"))
}

@Composable
private fun effectRow(
    effect: TextLabEffect,
    checked: Boolean,
    params: MutableMap<String, String>,
    family: String,
    fontSize: Float,
    onToggle: () -> Unit,
) {
    Row(tags = listOf("textlab-effect-row")) {
        Row(
            tags = listOf("textlab-toggle", "textlab-effect-name"),
            modifier = Modifier.input(hoverable = true, clickable = true).onClick { onToggle() },
        ) {
            Box(
                tags = listOf("textlab-check"),
                modifier = Modifier.background(
                    if (checked) UiColor(0.36f, 0.78f, 0.5f, 1f) else UiColor(0.3f, 0.34f, 0.4f, 1f),
                ),
            )
            Text(effect.label, tags = listOf("textlab-toggle-label"))
        }
        Row(tags = listOf("textlab-params")) {
            effect.params.forEachIndexed { index, param ->
                val key = effect.key(index)
                labField(param.label, params[key] ?: param.default, width = param.width) { params[key] = it }
            }
        }
        // The preview carries this one effect only, so a broken effect cannot be blamed on another.
        Text(
            effect.preview,
            tags = listOf("textlab-effect-preview"),
            modifier = Modifier
                .fontFamily(family)
                .fontSize(fontSize)
                .textEffects(effect.build(params)),
        )
    }
}

@Composable
private fun labField(label: String, value: String, width: Float, onChange: (String) -> Unit) {
    Row(tags = listOf("textlab-field")) {
        Text(label, tags = listOf("textlab-label"))
        TextField(
            value = value,
            tags = listOf("textlab-input"),
            modifier = Modifier.size(width.px, UiLength.Auto),
            onChange = onChange,
        )
    }
}

@Composable
private fun labPill(label: String, onClick: () -> Unit) {
    Row(
        tags = listOf("textlab-toggle"),
        modifier = Modifier.input(hoverable = true, clickable = true).onClick { onClick() },
    ) {
        Text(label, tags = listOf("textlab-toggle-label"))
    }
}

@Composable
private fun labToggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        tags = listOf("textlab-toggle"),
        modifier = Modifier.input(hoverable = true, clickable = true).onClick { onToggle() },
    ) {
        Box(
            tags = listOf("textlab-check"),
            modifier = Modifier.background(
                if (checked) UiColor(0.36f, 0.78f, 0.5f, 1f) else UiColor(0.3f, 0.34f, 0.4f, 1f),
            ),
        )
        Text(label, tags = listOf("textlab-toggle-label"))
    }
}

/** One font offered by the lab, covering all three glyph sources the engine can resolve. */
private class TextLabFont(val label: String, val short: String, val family: String)

private val TextLabFonts = listOf(
    TextLabFont("Vanilla · default", "vanilla", DefaultUiFontFamily),
    TextLabFont("MonoCraft · MSDF", "mono", "hollowengine:fonts/monocraft"),
    TextLabFont("Hack · MSDF", "hack", "hollowengine:fonts/hack"),
    TextLabFont("PT Sans · MSDF", "pt", "hollowengine:fonts/pt_sans"),
    TextLabFont("Roboto · MSDF", "roboto", "hollowengine:fonts/font-roboto-regular"),
    TextLabFont("Vanilla alt · bitmap", "alt", "vanilla:alt"),
    TextLabFont(
        "Noto Sans · TTF→MSDF",
        "noto",
        "hollowengine:fonts/noto_sans.ttf",
    ),
    TextLabFont(
        "ZHDH · TTF→MSDF",
        "zhdh",
        "hollowengine:fonts/font.ttf",
    ),
)

private val TextLabSizes = listOf(8f, 10f, 16f, 24f)

private const val Pangram = "Съешь ещё Wavy 123"

private const val DefaultSample =
    "Съешь же ещё этих мягких французских булок да выпей чаю. " +
            "The quick brown fox jumps over the lazy dog. 0123456789. " +
            "Длинноеслово-которое-негде-переносить проверяет разрыв по символам.\n" +
            "А жёсткий перенос строкой выше срабатывает даже с выключенным wrap."

/** A parameter of an effect, edited as text so any value (including a bad one) can be tried. */
private class TextLabParam(val label: String, val default: String, val width: Float = 34f)

private class TextLabEffect(
    val id: String,
    val label: String,
    val preview: String,
    val params: List<TextLabParam>,
    private val factory: (TextLabArgs) -> UiTextEffect,
) {
    fun key(index: Int) = "$id.$index"

    fun build(values: Map<String, String>): UiTextEffect = factory(TextLabArgs(this, values))
}

/** Reads an effect's parameters, falling back to the declared default when a field is unparsable. */
private class TextLabArgs(private val effect: TextLabEffect, private val values: Map<String, String>) {
    fun number(index: Int): Float {
        val default = effect.params[index].default.toFloatOrNull() ?: 0f
        return values[effect.key(index)]?.trim()?.toFloatOrNull() ?: default
    }

    fun int(index: Int): Int = number(index).toInt()

    /** Blank means "no override": the rule then takes the color the text is drawn in. */
    fun color(index: Int): UiColor? {
        val raw = values[effect.key(index)]?.trim().orEmpty().ifEmpty { effect.params[index].default }
        return parseHexColor(raw)
    }
}

/** `#RRGGBB` or `#RRGGBBAA`; anything else (including an empty field) means "no override". */
private fun parseHexColor(raw: String): UiColor? {
    val hex = raw.removePrefix("#")
    if (hex.length != 6 && hex.length != 8) return null
    val value = hex.toLongOrNull(16) ?: return null
    val shift = if (hex.length == 8) 8 else 0
    fun channel(bits: Int) = ((value shr bits) and 0xFF).toInt() / 255f
    return UiColor(
        channel(16 + shift),
        channel(8 + shift),
        channel(shift),
        if (shift == 0) 1f else channel(0),
    )
}

private val TextLabEffects = listOf(
    TextLabEffect(
        "bold", "bold", "Жирный Bold", listOf(TextLabParam("weight", "0.0625")),
    ) { Bold(it.number(0)) },
    TextLabEffect(
        "italic", "italic", "Наклонный Italic", listOf(TextLabParam("skew°", "12")),
    ) { Italic(it.number(0)) },
    TextLabEffect(
        "underline", "underline", "Подчёркнутый",
        listOf(TextLabParam("thick", "0"), TextLabParam("offset", "0"), TextLabParam("color", "", width = 58f)),
    ) { Underline(it.number(0), it.number(1), it.color(2)) },
    TextLabEffect(
        "strikethrough", "strikethrough", "Зачёркнутый",
        listOf(TextLabParam("thick", "0"), TextLabParam("offset", "0"), TextLabParam("color", "", width = 58f)),
    ) { Strikethrough(it.number(0), it.number(1), it.color(2)) },
    TextLabEffect("code", "code", "monospace()", emptyList()) { Code },
    TextLabEffect(
        "shadow", "shadow", "Тень Shadow",
        listOf(TextLabParam("dx", "1.5"), TextLabParam("dy", "1.5"), TextLabParam("blur", "0")),
    ) { Shadow(it.number(0), it.number(1), it.number(2)) },
    TextLabEffect(
        "outline", "outline", "Обводка Outline", listOf(TextLabParam("width", "1.5")),
    ) { Outline(it.number(0)) },
    TextLabEffect(
        "glow", "glow", "Свечение Glow",
        listOf(TextLabParam("radius", "3"), TextLabParam("quality", "4")),
    ) { Glow(it.number(0), quality = it.int(1).coerceIn(1, 16)) },
    TextLabEffect(
        "gradient", "gradient", "Градиент Gradient", listOf(TextLabParam("speed", "0")),
    ) { Gradient(speed = it.number(0)) },
    TextLabEffect(
        "rainbow", "rainbow", "Радуга Rainbow",
        listOf(TextLabParam("freq", "0.5"), TextLabParam("speed", "0.5")),
    ) { Rainbow(frequency = it.number(0), speed = it.number(1)) },
    TextLabEffect(
        "pulse", "pulse", "Пульс Pulse",
        listOf(TextLabParam("freq", "1.5"), TextLabParam("ampl", "0.4"), TextLabParam("min α", "0.3")),
    ) { Pulse(it.number(0), it.number(1), it.number(2)) },
    TextLabEffect(
        "wave", "wave", "Волна Wave",
        listOf(TextLabParam("ampl", "3"), TextLabParam("freq", "1.5"), TextLabParam("speed", "2")),
    ) { Wave(it.number(0), it.number(1), it.number(2)) },
    TextLabEffect(
        "shake", "shake", "Тряска Shake",
        listOf(TextLabParam("ampl", "2"), TextLabParam("freq", "10")),
    ) { Shake(it.number(0), it.number(1)) },
    TextLabEffect(
        "wiggle", "wiggle", "Виляние Wiggle",
        listOf(
            TextLabParam("ampl", "2"), TextLabParam("freq", "2"),
            TextLabParam("speed", "1.5"), TextLabParam("angle°", "0"),
        ),
    ) { Wiggle(it.number(0), it.number(1), it.number(2), it.number(3)) },
    TextLabEffect(
        "swing", "swing", "Качание Swing",
        listOf(TextLabParam("ampl", "5"), TextLabParam("freq", "0.8"), TextLabParam("speed", "1.2")),
    ) { Swing(it.number(0), it.number(1), it.number(2)) },
    TextLabEffect(
        "glitch", "glitch", "Глитч Glitch",
        listOf(TextLabParam("freq", "3"), TextLabParam("intens", "2")),
    ) { Glitch(it.number(0), it.number(1)) },
)

/** Combinations worth watching: each pair below has broken at least once on its own. */
private class TextLabCombo(val label: String, val effects: List<UiTextEffect>)

private val TextLabCombos = listOf(
    TextLabCombo("plain", emptyList()),
    TextLabCombo("bold", listOf(Bold())),
    TextLabCombo("heavy", listOf(Bold(0.16f))),
    TextLabCombo("italic", listOf(Italic())),
    TextLabCombo("bold+italic", listOf(Bold(), Italic())),
    TextLabCombo("rules", listOf(Underline(), Strikethrough())),
    TextLabCombo("bold+rules", listOf(Bold(0.12f), Italic(18f), Underline())),
    TextLabCombo("shadow+bold", listOf(Bold(), Shadow())),
    TextLabCombo("outline+italic", listOf(Italic(), Outline(1f))),
    TextLabCombo("wave+underline", listOf(Wave(), Underline())),
    TextLabCombo("rainbow+bold", listOf(Bold(), Rainbow())),
)
