package ru.hollowhorizon.hollowengine.client.ui.text

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.utils.font.*
import ru.hollowhorizon.hollowengine.common.config.HollowEngineConfig
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

/**
 * TrueType/OpenType fonts, drawn from an atlas page that fills up as characters are met.
 */
object UiTtfFont {
    private const val Extension = ".ttf"

    private const val PageSize = 2048
    private const val BakeQueueCapacity = 64

    private val families = ConcurrentHashMap<String, FamilyState>()

    private val bakers: ExecutorService = run {
        val threads = Runtime.getRuntime().availableProcessors().minus(1).coerceIn(1, 4)
        ThreadPoolExecutor(
            threads, threads, 0L, TimeUnit.MILLISECONDS,
            PriorityBlockingQueue(BakeQueueCapacity, compareBy { (it as BakeTask).order }),
        ) { runnable -> Thread(runnable, "HollowEngine MSDF glyph baker").apply { isDaemon = true } }
    }

    private class BakeTask(urgent: Boolean, private val action: () -> Unit) : Runnable {
        val order: Long = (if (urgent) 0L else 1L shl 62) + sequence.getAndIncrement()

        override fun run() = action()

        private companion object {
            val sequence = AtomicLong()
        }
    }

    private fun bake(urgent: Boolean, action: () -> Unit) = bakers.execute(BakeTask(urgent, action))

    /** A family is a TrueType font when the file it names, ahead of any `?` options, ends in `.ttf`. */
    fun isTtfFamily(family: String): Boolean = family.substringBefore('?').trim().endsWith(Extension, ignoreCase = true)

    fun metrics(family: String): UiMsdfFontMetrics? = (request(family) as? FamilyState.Open)?.face?.metrics

    fun glyphFont(family: String): UiGlyphFont? {
        val state = request(family) as? FamilyState.Open ?: return null
        state.font?.let { return it }
        return runCatching { state.openPage() }
            .onFailure { HollowEngine.LOGGER.error("Could not create the glyph page for '$family'", it) }
            .getOrNull()
    }

    fun pumpGlyphs() {
        for ((family, state) in families) {
            val open = state as? FamilyState.Open ?: continue
            val atlas = open.atlas ?: continue
            open.handOverPrebaked(atlas)
            atlas.pumpUploads()
            for (codepoint in atlas.takeRequests()) bake(urgent = true) { open.bake(family, codepoint) }
        }
    }

    fun unloadAll() {
        families.values.forEach { (it as? FamilyState.Open)?.close() }
        families.clear()
    }

    /**
     * The face is opened right here, on the first ask. Text is measured with its metrics, and a
     * stand-in used until it opened would leave every node laid out in that time at the stand-in's
     * widths, with nothing to lay it out again. Only the baking of glyphs is left to other threads.
     */
    private fun request(family: String): FamilyState? {
        families[family]?.let { return it }
        if (!isTtfFamily(family)) return null
        return families.computeIfAbsent(family) { open(family) }
    }

    private fun open(family: String): FamilyState {
        val request = UiTtfFontRequest.parse(family) ?: run {
            HollowEngine.LOGGER.warn("Malformed TrueType font family '{}'", family)
            return FamilyState.Failed
        }
        val opened = openFace(family, request)
        (opened as? FamilyState.Open)?.let { open ->
            for (codepoint in open.spec.codepoints) bake(urgent = false) { open.prebake(family, codepoint) }
        }
        return opened
    }

    private fun openFace(family: String, request: UiTtfFontRequest): FamilyState = runCatching {
        val spec = request.bakeSpec()
        FamilyState.Open(OpenFace(TtfFace.open(request.readBytes()), spec), spec)
    }.getOrElse { throwable ->
        HollowEngine.LOGGER.error("Could not open TrueType font '$family'", throwable)
        FamilyState.Failed
    }

    private sealed interface FamilyState {
        data object Failed : FamilyState

        class Open(val face: OpenFace, val spec: MsdfBakeSpec) : FamilyState {
            @Volatile
            var atlas: DynamicGlyphAtlas? = null
                private set

            @Volatile
            var font: UiDynamicGlyphFont? = null
                private set

            private var texture: GlyphAtlasTexture? = null

            private val prebaked = ConcurrentLinkedQueue<Delivery>()

            fun openPage(): UiDynamicGlyphFont {
                font?.let { return it }
                val page = GlyphAtlasTexture(PageSize)
                val atlas = DynamicGlyphAtlas(PageSize, spec.pixelSize, page)
                for (codepoint in spec.codepoints) atlas.expect(codepoint)
                val font = UiDynamicGlyphFont(face.metrics, atlas, page, spec)
                texture = page
                this.atlas = atlas
                this.font = font
                return font
            }

            fun handOverPrebaked(atlas: DynamicGlyphAtlas) {
                while (true) {
                    val delivery = prebaked.poll() ?: return
                    atlas.deliver(delivery.codepoint, delivery.cell, delivery.advance)
                }
            }

            fun bake(family: String, codepoint: Int) {
                val atlas = atlas ?: return
                val delivery = bakeCell(family, codepoint)
                atlas.deliver(codepoint, delivery.cell, delivery.advance)
            }

            fun prebake(family: String, codepoint: Int) {
                prebaked += bakeCell(family, codepoint)
            }

            private fun bakeCell(family: String, codepoint: Int): Delivery {
                val baked = runCatching {
                    val outline = face.withFace { it.loadGlyph(codepoint, spec.pixelSize) }
                    outline?.let { bakeGlyphField(it, face.unitsPerEm, spec)?.toBakedCell() to it.advance }
                }.onFailure {
                    HollowEngine.LOGGER.warn("Could not bake U+{} of '{}'", codepoint.toString(16), family)
                }.getOrNull()
                return Delivery(codepoint, baked?.first, baked?.second ?: 0f)
            }

            private class Delivery(val codepoint: Int, val cell: BakedGlyphCell?, val advance: Float)

            fun close() {
                texture?.close()
                face.close()
            }
        }
    }

    internal class OpenFace(private val face: TtfFace, spec: MsdfBakeSpec) : UiGlyphAdvances {
        private val lock = Any()
        private val advanceCache = ConcurrentHashMap<Int, Float>()
        private var closed = false

        val unitsPerEm: Float = face.unitsPerEm

        val metrics: UiMsdfFontMetrics = UiMsdfFontMetrics(
            meta = MsdfMeta(
                atlas = MsdfAtlasInfo("msdf", spec.pixelRange, spec.pixelSize, 0, 0, "bottom"),
                name = face.familyName,
                metrics = MsdfMetrics(
                    emSize = 1f,
                    lineHeight = face.lineHeight,
                    ascender = face.ascender,
                    descender = face.descender,
                    underlineY = face.underlineY,
                    underlineThickness = face.underlineThickness,
                ),
            ),
            glyphMap = emptyMap(),
            advances = this,
        )

        override fun advanceOf(codepoint: Int): Float? {
            advanceCache[codepoint]?.let { return it.takeIf { cached -> !cached.isNaN() } }
            val advance = withFace { it.advanceOf(codepoint) }
            advanceCache[codepoint] = advance ?: Float.NaN
            return advance
        }

        fun <T> withFace(action: (TtfFace) -> T): T? = synchronized(lock) { if (closed) null else action(face) }

        fun close() = synchronized(lock) {
            if (!closed) {
                closed = true
                face.close()
            }
        }
    }
}

internal class UiTtfFontRequest(
    val source: String,
    private val location: ResourceLocation?,
    private val filePath: String?,
    private val pixelSize: Float,
    private val pixelRange: Float,
    private val codepoints: IntArray,
) {
    fun bakeSpec() = MsdfBakeSpec(pixelSize, pixelRange, codepoints)

    fun readBytes(): ByteArray = when {
        location != null -> Minecraft.getInstance().resourceManager.getResourceOrThrow(location)
            .open().use { it.readBytes() }

        filePath != null -> Files.readAllBytes(
            Minecraft.getInstance().gameDirectory.toPath().resolve(filePath).normalize()
        )

        else -> error("no font source")
    }

    companion object {
        private const val DefaultPixelSize = 48f
        private const val DefaultPixelRange = 2f
        private const val FilePrefix = "file:"
        private const val FallbackCharset = "latin+latin-ext+cyrillic+punctuation"

        private fun defaultCharset(): String =
            runCatching { HollowEngineConfig.fontPreloadCharset }.getOrDefault(FallbackCharset)

        fun parse(family: String): UiTtfFontRequest? {
            val source = family.substringBefore('?').trim()
            if (source.isEmpty()) return null
            val options = family.substringAfter('?', "").split('&')
                .mapNotNull { option ->
                    val name = option.substringBefore('=').trim().lowercase()
                    if (name.isEmpty()) null else name to option.substringAfter('=', "").trim()
                }
                .toMap()

            val isFile = source.startsWith(FilePrefix)
            val location = if (isFile) null else ResourceLocation.tryParse(source) ?: return null
            return UiTtfFontRequest(
                source = source,
                location = location,
                filePath = source.removePrefix(FilePrefix).takeIf { isFile },
                pixelSize = options["size"]?.toFloatOrNull()?.coerceIn(8f, 256f) ?: DefaultPixelSize,
                pixelRange = options["range"]?.toFloatOrNull()?.coerceIn(1f, 32f) ?: DefaultPixelRange,
                codepoints = parseCharset(options["charset"] ?: defaultCharset()),
            )
        }

        /**
         * `+`-separated preset names and explicit `U+XXXX` / `U+XXXX-U+YYYY` ranges. `+` rather than
         * `,` keeps the whole family usable as a single HSS argument.
         *
         * This is the set kept ready before the font is first drawn; anything outside it still
         * renders, it just arrives a few frames after it first appears.
         */
        private fun parseCharset(specification: String): IntArray {
            val codepoints = sortedSetOf<Int>()
            for (token in specification.split('+', ' ').map { it.trim().lowercase() }) {
                if (token.isEmpty()) continue
                when (token) {
                    "none" -> Unit
                    "ascii" -> codepoints.addRange(0x20, 0x7E)
                    "latin" -> {
                        codepoints.addRange(0x20, 0x7E)
                        codepoints.addRange(0xA0, 0xFF)
                    }

                    "latin-ext" -> codepoints.addRange(0x100, 0x17F)
                    "cyrillic" -> {
                        codepoints.addRange(0x400, 0x45F)
                        codepoints.addRange(0x490, 0x4FF)
                    }

                    "greek" -> codepoints.addRange(0x370, 0x3FF)
                    "hiragana" -> codepoints.addRange(0x3041, 0x309F)
                    "katakana" -> codepoints.addRange(0x30A0, 0x30FF)
                    "punctuation" -> {
                        codepoints.addRange(0x2010, 0x2027)
                        codepoints.addRange(0x2030, 0x205E)
                        codepoints += listOf(0x20AC, 0x2116, 0x2122, 0x2190, 0x2192, 0xFFFD)
                    }

                    else -> parseCodepointToken(token)?.let { (first, last) -> codepoints.addRange(first, last) }
                }
            }
            return codepoints.toIntArray()
        }

        /** `u+41`, or `u+400-u+4ff` for a range. Malformed tokens are dropped rather than fatal. */
        private fun parseCodepointToken(token: String): Pair<Int, Int>? {
            val parts = token.split('-')
            val first = parts.getOrNull(0)?.removePrefix("u+")?.toIntOrNull(16) ?: return null
            val last = parts.getOrNull(1)?.removePrefix("u+")?.toIntOrNull(16) ?: first
            if (last < first || last > Char.MAX_VALUE.code) return null
            return first to last
        }

        private fun MutableSet<Int>.addRange(first: Int, last: Int) {
            for (codepoint in first..last) add(codepoint)
        }
    }
}
