package ru.hollowhorizon.hollowengine.client.utils.font

import java.util.concurrent.ConcurrentLinkedQueue

/** Writes one glyph's field into a page. The cell is bottom-origin, as the baker lays cells out. */
internal fun interface GlyphCellUploader {
    fun upload(x: Int, y: Int, width: Int, height: Int, rgb: ByteArray)
}

/**
 * An atlas page that fills up as codepoints are met, instead of being baked whole up front.
 */
internal class DynamicGlyphAtlas(
    pageSize: Int,
    private val pixelSize: Float,
    private val uploader: GlyphCellUploader,
) {
    private val shelves = GlyphAtlasShelves(pageSize, pageSize)
    private val resident = HashMap<Int, ResidentGlyph>()
    private val requested = LinkedHashSet<Int>()
    private val inFlight = HashSet<Int>()
    private val delivered = ConcurrentLinkedQueue<Delivery>()
    private val rejected = HashSet<Int>()

    /** Bumped on every hit, so eviction can tell a glyph in use from one seen once. */
    private var clock = 0L

    /**
     * Bumped whenever a cell changes hands. Anything caching where a glyph sits has to notice:
     * the cell an evicted glyph used is about to hold a different picture.
     */
    var epoch: Int = 0
        private set

    var evictions: Int = 0
        private set

    /**
     * The glyph's place in the page, or null while it is not there yet.
     */
    fun glyphOf(codepoint: Int): MsdfGlyph? {
        resident[codepoint]?.let {
            it.lastUsed = ++clock
            it.uses++
            return it.glyph
        }
        if (wanted(codepoint)) requested += codepoint
        return null
    }

    private fun wanted(codepoint: Int): Boolean =
        codepoint !in resident && codepoint !in inFlight && codepoint !in rejected

    fun takeRequests(limit: Int = Int.MAX_VALUE): List<Int> {
        if (requested.isEmpty()) return emptyList()
        val batch = ArrayList<Int>(minOf(limit, requested.size))
        val iterator = requested.iterator()
        while (iterator.hasNext() && batch.size < limit) {
            val codepoint = iterator.next()
            iterator.remove()
            batch += codepoint
            inFlight += codepoint
        }
        return batch
    }

    fun deliver(codepoint: Int, cell: BakedGlyphCell?, advance: Float) {
        delivered += Delivery(codepoint, cell, advance)
    }

    fun expect(codepoint: Int) {
        if (wanted(codepoint)) inFlight += codepoint
    }

    fun pumpUploads(limit: Int = Int.MAX_VALUE): Int {
        var uploaded = 0
        while (uploaded < limit) {
            val delivery = delivered.poll() ?: break
            inFlight -= delivery.codepoint
            val cell = delivery.cell
            if (cell == null) {
                resident[delivery.codepoint] = ResidentGlyph(
                    MsdfGlyph(delivery.codepoint, delivery.advance),
                    lastUsed = ++clock,
                )
                continue
            }
            val slot = shelves.allocate(cell.width, cell.height) ?: evictFor(cell.width, cell.height)
            if (slot == null) {
                rejected += delivery.codepoint
                continue
            }
            uploader.upload(slot.x, slot.y, cell.width, cell.height, cell.rgb)
            epoch++
            resident[delivery.codepoint] = ResidentGlyph(
                glyph = cell.toMsdfGlyph(delivery.codepoint, delivery.advance, slot, pixelSize),
                lastUsed = ++clock,
                slot = slot,
            )
            uploaded++
        }
        return uploaded
    }

    private fun evictFor(cellWidth: Int, cellHeight: Int): GlyphAtlasSlot? {
        for (entry in resident.values) entry.uses = (entry.uses + 1) / 2
        val victims = resident.entries.filter { it.value.slot != null }
            .sortedWith(compareBy({ it.value.uses }, { it.value.lastUsed }))
        var evicted = 0
        for ((codepoint, entry) in victims) {
            if (evicted >= MaxEvictionsPerCell) break
            shelves.release(entry.slot ?: continue)
            resident -= codepoint
            evicted++
            evictions++
            epoch++
            shelves.allocate(cellWidth, cellHeight)?.let {
                rejected.clear()
                return it
            }
        }
        if (evicted > 0) rejected.clear()
        return null
    }

    private class ResidentGlyph(
        val glyph: MsdfGlyph,
        var lastUsed: Long,
        val slot: GlyphAtlasSlot? = null,
        var uses: Int = 1,
    )

    private class Delivery(val codepoint: Int, val cell: BakedGlyphCell?, val advance: Float)

    private companion object {
        const val MaxEvictionsPerCell = 16
    }
}

internal class BakedGlyphCell(
    val width: Int,
    val height: Int,
    val pixelLeft: Float,
    val pixelBottom: Float,
    val rgb: ByteArray,
)

internal fun MsdfGlyphField.toBakedCell(): BakedGlyphCell =
    BakedGlyphCell(width, height, pixelLeft, pixelBottom, toDistanceBytes())

internal fun BakedGlyphCell.toMsdfGlyph(
    codepoint: Int,
    advance: Float,
    slot: GlyphAtlasSlot,
    pixelSize: Float,
): MsdfGlyph = MsdfGlyph(
    unicode = codepoint,
    advance = advance,
    planeBounds = MsdfRect(
        left = pixelLeft / pixelSize,
        bottom = pixelBottom / pixelSize,
        right = (pixelLeft + width) / pixelSize,
        top = (pixelBottom + height) / pixelSize,
    ),
    atlasBounds = MsdfRect(
        left = slot.x.toFloat(),
        bottom = slot.y.toFloat(),
        right = (slot.x + width).toFloat(),
        top = (slot.y + height).toFloat(),
    ),
)
