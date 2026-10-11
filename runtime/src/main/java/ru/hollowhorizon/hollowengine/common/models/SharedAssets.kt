package ru.hollowhorizon.hollowengine.common.models

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.api.ReloadListener
import ru.hollowhorizon.hollowengine.common.utils.Side
import java.io.InputStream

/**
 * A kind of file under `assets` that both sides read by the same location: the client from its resource
 * packs, the server from the hollowengine folder and the jars. A resource pack only changes what the
 * client draws; the server keeps playing its own copy.
 */
class SharedAssets<T : Any>(private val kind: String, private val decode: (InputStream) -> T) {
    private val client = SideCache<T>()
    private val server = SideCache<T>()

    init {
        SharedAssetsReload.register(this)
    }

    fun of(location: ResourceLocation, client: Boolean): T? {
        val cache = if (client) this.client else server
        cache.entries[location]?.let { return it.value }
        val value = read(location, client)
        cache.entries += location to Entry(value)
        return value
    }

    /** Puts [value] in place of what both sides read at [location], until their next reload. */
    fun put(location: ResourceLocation, value: T) {
        client.entries += location to Entry(value)
        server.entries += location to Entry(value)
    }

    internal fun clear(client: Boolean) {
        (if (client) this.client else server).entries = emptyMap()
    }

    private fun read(location: ResourceLocation, client: Boolean): T? = runCatching {
        if (client) SharedAssetsReload.clientResources?.getResource(location)?.orElse(null)?.open()?.use(decode)
        else if (ModelResourceIO.exists(location)) ModelResourceIO.open(location).use(decode)
        else null
    }.onFailure { HollowEngine.LOGGER.warn("Could not read {} '{}': {}", kind, location, it.message) }.getOrNull()

    private class Entry<T>(val value: T?)

    /** Replaced whole, never changed in place: the IDE writes it from the client thread while the server reads. */
    private class SideCache<T> {
        @Volatile
        var entries: Map<ResourceLocation, Entry<T>> = emptyMap()
    }
}

/** Drops what every [SharedAssets] has read on a side when that side reloads its resources. */
@ReloadListener
internal object SharedAssetsReload : ResourceManagerReloadListener {
    @Volatile
    private var all: List<SharedAssets<*>> = emptyList()

    /** What the client reads its side from; set on its first resource reload. */
    @Volatile
    var clientResources: ResourceManager? = null
        private set

    internal fun clientReloaded(resourceManager: ResourceManager) {
        clientResources = resourceManager
        all.forEach { it.clear(client = true) }
    }

    @Synchronized
    fun register(assets: SharedAssets<*>) {
        all = all + assets
    }

    override fun onResourceManagerReload(resourceManager: ResourceManager) {
        all.forEach { it.clear(client = false) }
    }
}

/** The client half of [SharedAssetsReload]. */
@ReloadListener(Side.CLIENT)
internal object SharedAssetsClientReload : ResourceManagerReloadListener {
    override fun onResourceManagerReload(resourceManager: ResourceManager) = SharedAssetsReload.clientReloaded(resourceManager)
}
