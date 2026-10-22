package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.launch
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.coroutines.runtimeContext
import java.util.UUID

/**
 * Undos and fast-forwards that need an entity which is not loaded: a player who is offline, a mob in an unloaded
 * chunk. Skipping them would leave the world as the story no longer is, so they wait for the entity and run when it
 * loads. They are kept with the server, apart from any story, so a story that ends meanwhile still gets them done.
 */
internal object StoryDeferredReplays {
    private enum class Kind { UNDO, FAST_FORWARD }

    private class Deferred(val kind: Kind, val entry: JournalEntry)

    private val waiting = LinkedHashMap<UUID, MutableList<Deferred>>()

    /** What [save] wrote for this server, changed since: the owner of the queue should save it. */
    var changed = false
        private set

    fun undo(entry: JournalEntry, entity: UUID) = defer(Kind.UNDO, entry, entity)

    fun fastForward(entry: JournalEntry, entity: UUID) = defer(Kind.FAST_FORWARD, entry, entity)

    /** [entity] loaded: runs what waited for it, in the order it was deferred. */
    fun entityLoaded(entity: Entity) {
        val deferred = waiting.remove(entity.uuid) ?: return
        changed = true
        deferred.forEach { replay ->
            when (replay.kind) {
                Kind.UNDO -> StoryActions.undo(replay.entry)
                Kind.FAST_FORWARD -> {
                    val server = entity.server ?: return@forEach
                    server.runtimeContext.scope.launch { StoryActions.fastForward(replay.entry) }
                }
            }
        }
    }

    fun save(): CompoundTag = CompoundTag().apply {
        waiting.forEach { (uuid, deferred) ->
            put(uuid.toString(), ListTag().apply {
                deferred.forEach { replay ->
                    add(replay.entry.save().apply { putString("deferred", replay.kind.name) })
                }
            })
        }
        changed = false
    }

    fun load(tag: CompoundTag) {
        waiting.clear()
        tag.allKeys.forEach { key ->
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach
            waiting[uuid] = tag.getList(key, Tag.TAG_COMPOUND.toInt()).mapTo(ArrayList()) {
                val saved = it as CompoundTag
                Deferred(runCatching { Kind.valueOf(saved.getString("deferred")) }.getOrDefault(Kind.UNDO), JournalEntry.load(saved))
            }
        }
        changed = false
    }

    fun clear() {
        waiting.clear()
        changed = false
    }

    private fun defer(kind: Kind, entry: JournalEntry, entity: UUID) {
        HollowEngine.LOGGER.info("Story '{}' waits for entity {} to {} '{}'", entry.run, entity, kind.name.lowercase(), entry.action)
        waiting.getOrPut(entity, ::ArrayList) += Deferred(kind, entry)
        changed = true
    }
}
