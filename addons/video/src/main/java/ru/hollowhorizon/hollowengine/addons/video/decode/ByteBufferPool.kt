package ru.hollowhorizon.hollowengine.addons.video.decode

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class ByteBufferPool(
    private val maxPooledBuffers: Int = 96,
) {
    private val pools = ConcurrentHashMap<Int, ConcurrentLinkedQueue<ByteBuffer>>()
    private val pooledCount = AtomicInteger(0)

    fun acquire(minSize: Int): ByteBuffer {
        val bucket = bucketSize(minSize)
        val existing = pools[bucket]?.poll()
        if (existing != null) {
            pooledCount.decrementAndGet()
            existing.clear()
            existing.limit(minSize)
            return existing
        }
        return ByteBuffer.allocateDirect(bucket).also { it.limit(minSize) }
    }

    fun release(buffer: ByteBuffer) {
        val bucket = buffer.capacity()
        if (pooledCount.incrementAndGet() > maxPooledBuffers) {
            pooledCount.decrementAndGet()
            return
        }
        buffer.clear()
        pools.computeIfAbsent(bucket) { ConcurrentLinkedQueue() }.offer(buffer)
    }

    private fun bucketSize(size: Int): Int {
        var bucket = 1
        while (bucket < size) bucket = bucket shl 1
        return bucket
    }
}