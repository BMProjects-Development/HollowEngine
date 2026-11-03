package ru.hollowhorizon.hollowengine.addons.lighting.client

import net.minecraft.client.Minecraft
import org.lwjgl.system.MemoryUtil
import org.qualet.irl.light.CookieArrayBase
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.utils.rl

/** Engine resource IDs keep cookies usable from addon assets and exported projects alike. */
internal object LightCookies : CookieArrayBase(16) {
    private val layers = HashMap<String, Int>()
    private var nextLayer = 0

    fun texture(): Int = textureId()

    fun resolve(name: String): Int {
        if (name.isBlank()) return -1
        layers[name]?.let { return it }
        if (nextLayer >= 16) return -1
        return runCatching {
            val resource = Minecraft.getInstance().resourceManager.getResource(name.rl).orElseThrow()
            val pixels =
                resource.open().use { decode(it.readAllBytes()) } ?: error("Could not decode light cookie '$name'")
            try {
                val layer = nextLayer
                uploadLayer(pixels, layer)
                nextLayer++
                layer
            } finally {
                MemoryUtil.memFree(pixels)
            }
        }.onFailure { HollowEngine.LOGGER.warn("Could not load light cookie '{}'", name, it) }.getOrDefault(-1)
            .also { layers[name] = it }
    }

    fun close() {
        deleteTexture()
        layers.clear()
        nextLayer = 0
    }
}
