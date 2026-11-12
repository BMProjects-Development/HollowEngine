package ru.hollowhorizon.hollowengine.common.network

import net.minecraft.server.level.ChunkTrackingView
import net.minecraft.world.level.ChunkPos
import ru.hollowhorizon.hollowengine.bridge.world.CameraChunkTrackingView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraChunkTrackingTest {
    @Test
    fun movingAndClosingPreviewKeepsThePlayerChunks() {
        val player = view(0, 0)
        val firstCamera = view(100, 100)
        val nextCamera = view(101, 100)
        val before = CameraChunkTrackingView(player, listOf(firstCamera))
        val after = CameraChunkTrackingView(player, listOf(nextCamera))
        val added = mutableListOf<ChunkPos>()
        val dropped = mutableListOf<ChunkPos>()

        CameraChunkTrackingView.difference(before, after, added::add, dropped::add)

        assertEquals(positions(nextCamera) - positions(firstCamera), added.toSet())
        assertEquals(positions(firstCamera) - positions(nextCamera), dropped.toSet())
        assertTrue((added + dropped).none(player::contains))

        added.clear()
        dropped.clear()
        CameraChunkTrackingView.difference(after, player, added::add, dropped::add)
        assertTrue(added.isEmpty())
        assertEquals(positions(nextCamera), dropped.toSet())
    }

    @Test
    fun overlappingCameraViewsEmitEachChunkOnce() {
        val player = view(0, 0)
        val overlapping = view(2, 0)
        val remote = view(-100, -100)
        val combined = CameraChunkTrackingView(player, listOf(overlapping, remote))
        val emitted = mutableListOf<ChunkPos>()
        combined.forEach(emitted::add)

        assertEquals(positions(player) + positions(overlapping) + positions(remote), emitted.toSet())
        assertEquals(emitted.size, emitted.distinct().size)
        assertTrue(combined.contains(ChunkPos(-100, -100)))
        assertFalse(combined.contains(ChunkPos(50, 50)))
    }

    private fun view(x: Int, z: Int) = ChunkTrackingView.Positioned(ChunkPos(x, z), 4)

    private fun positions(view: ChunkTrackingView): Set<ChunkPos> = buildSet { view.forEach(::add) }
}
