package ru.hollowhorizon.hollowengine.common.entities.objects

import kotlinx.serialization.Serializable
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.level.saveddata.SavedData
import ru.hollowhorizon.hollowengine.common.attachments.editor.canEditEntities
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForUuid
import java.util.UUID

/** A bookmarked object, remembered where it was last seen so it can be found while it is not loaded. */
@Serializable
data class WorldObjectFavorite(
    val uuid: @Serializable(ForUuid::class) UUID,
    val name: String,
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
)

/**
 * The objects the team bookmarked, kept with the world rather than by each player, so a large project
 * has one list of the places worth jumping to.
 */
class WorldObjectFavorites private constructor() : SavedData() {
    private val entries = LinkedHashMap<UUID, WorldObjectFavorite>()

    val all: List<WorldObjectFavorite> get() = entries.values.toList()

    operator fun contains(uuid: UUID): Boolean = uuid in entries

    operator fun get(uuid: UUID): WorldObjectFavorite? = entries[uuid]

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag {
        val list = ListTag()
        entries.values.forEach { favorite ->
            list += CompoundTag().apply {
                putUUID("Id", favorite.uuid)
                putString("Name", favorite.name)
                putString("Dimension", favorite.dimension)
                putDouble("X", favorite.x)
                putDouble("Y", favorite.y)
                putDouble("Z", favorite.z)
            }
        }
        tag.put("Favorites", list)
        return tag
    }

    companion object {
        private const val NAME = "hollowengine_object_favorites"

        /**
         * Vanilla, unlike NeoForge, runs every saved file through a fixer and has no case for none. The data is
         * written at the current version, so the level fixer leaves it as it is.
         */
        private val factory = Factory(::WorldObjectFavorites, ::load, DataFixTypes.LEVEL)

        fun of(server: MinecraftServer): WorldObjectFavorites = server.overworld().dataStorage.computeIfAbsent(factory, NAME)

        private fun load(tag: CompoundTag, registries: HolderLookup.Provider) = WorldObjectFavorites().apply {
            tag.getList("Favorites", Tag.TAG_COMPOUND.toInt()).forEach { element ->
                val entry = element as? CompoundTag ?: return@forEach
                if (!entry.hasUUID("Id")) return@forEach
                val favorite = WorldObjectFavorite(
                    entry.getUUID("Id"),
                    entry.getString("Name"),
                    entry.getString("Dimension"),
                    entry.getDouble("X"),
                    entry.getDouble("Y"),
                    entry.getDouble("Z"),
                )
                entries[favorite.uuid] = favorite
            }
        }

        /** Bookmarks [target] and tells everyone who edits the world. */
        fun add(target: WorldObjectEntity) {
            val server = target.server ?: return
            of(server).put(entryOf(target))
            broadcast(server)
        }

        /** Drops the bookmark of the object with [uuid], loaded or not. */
        fun remove(server: MinecraftServer, uuid: UUID) {
            of(server).remove(uuid)
            broadcast(server)
        }

        /** Keeps a bookmarked object's name and place current; called whenever the object is saved. */
        internal fun refresh(target: WorldObjectEntity) {
            val server = target.server ?: return
            val favorites = of(server)
            if (target.uuid !in favorites) return
            val entry = entryOf(target)
            if (favorites[target.uuid] != entry) favorites.put(entry)
        }

        /** The object is gone for good, and its bookmark with it. */
        internal fun forget(target: WorldObjectEntity) {
            val server = target.server ?: return
            val favorites = of(server)
            if (target.uuid !in favorites) return
            favorites.remove(target.uuid)
            broadcast(server)
        }

        fun broadcast(server: MinecraftServer) {
            val packet = WorldObjectFavoritesPacket(of(server).all)
            server.playerList.players.filter { it.canEditEntities() }.forEach { packet.send(it) }
        }

        private fun entryOf(target: WorldObjectEntity) = WorldObjectFavorite(
            target.uuid,
            target.name.string,
            target.level().dimension().location().toString(),
            target.x,
            target.y,
            target.z,
        )
    }

    private fun put(favorite: WorldObjectFavorite) {
        entries[favorite.uuid] = favorite
        setDirty()
    }

    private fun remove(uuid: UUID) {
        if (entries.remove(uuid) != null) setDirty()
    }
}
