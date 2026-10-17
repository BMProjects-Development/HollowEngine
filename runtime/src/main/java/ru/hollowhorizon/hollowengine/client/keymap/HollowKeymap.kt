package ru.hollowhorizon.hollowengine.client.keymap

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.apache.logging.log4j.LogManager
import ru.hollowhorizon.hollowengine.common.config.Config
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Every action of the editor and the chords the user gave them. Only what differs from an action's defaults is
 * written, to `config/hollowengine/keymap.json`; bindings of actions that are not registered right now (an addon that
 * is not loaded) are kept as they are, so they survive until it comes back.
 */
object HollowKeymap {
    private val LOGGER = LogManager.getLogger("HollowKeymap")
    private val format = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    private val json = Json { prettyPrint = true }

    private val registered = LinkedHashMap<String, HollowKeyAction>()
    private val overrides = mutableStateMapOf<String, List<HollowKeyChord>>()
    private val unknown = LinkedHashMap<String, List<String>>()
    private var loaded = false

    private val file: Path get() = Config.CONFIG_DIR.resolve("hollowengine").resolve("keymap.json")

    /** Every registered action, the engine's first, in the order they were declared. */
    val actions: List<HollowKeyAction>
        get() {
            HollowKeys.init()
            return registered.values.toList()
        }

    /** Adds [action]; addons call it for their own. */
    fun register(action: HollowKeyAction): HollowKeyAction {
        check(registered.putIfAbsent(action.id, action) == null) { "Key action '${action.id}' is registered twice" }
        unknown.remove(action.id)?.let { stored -> overrides[action.id] = stored.mapNotNull(HollowKeyChord::parse) }
        return action
    }

    fun bindingsOf(action: HollowKeyAction): List<HollowKeyChord> {
        ensureLoaded()
        return overrides[action.id] ?: action.defaults
    }

    fun isChanged(action: HollowKeyAction): Boolean {
        ensureLoaded()
        return action.id in overrides
    }

    fun rebind(action: HollowKeyAction, chords: List<HollowKeyChord>) {
        ensureLoaded()
        val distinct = chords.distinct()
        if (distinct == action.defaults) overrides.remove(action.id) else overrides[action.id] = distinct
        save()
    }

    fun reset(action: HollowKeyAction) = rebind(action, action.defaults)

    fun resetAll() {
        ensureLoaded()
        overrides.clear()
        save()
    }

    /** The other actions of [action]'s context that [chord] already triggers. */
    fun conflicts(action: HollowKeyAction, chord: HollowKeyChord): List<HollowKeyAction> =
        actions.filter { it !== action && it.context === action.context && chord in it.bindings }

    /** Writes the user's bindings, in the keymap file's format, to [target]. */
    fun export(target: Path) {
        ensureLoaded()
        target.createParentDirectories()
        target.writeText(json.encodeToString(format, stored()))
    }

    /** Replaces the user's bindings with the ones in [source]; false when it cannot be read. */
    fun import(source: Path): Boolean {
        val read = read(source) ?: return false
        overrides.clear()
        unknown.clear()
        apply(read)
        save()
        return true
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (file.exists()) read(file)?.let(::apply)
    }

    private fun apply(stored: Map<String, List<String>>) {
        stored.forEach { (id, chords) ->
            if (registered.containsKey(id)) overrides[id] = chords.mapNotNull(HollowKeyChord::parse) else unknown[id] =
                chords
        }
    }

    private fun read(path: Path): Map<String, List<String>>? = try {
        json.decodeFromString(format, path.readText())
    } catch (e: Exception) {
        LOGGER.warn("Could not read the keymap from {}", path, e)
        null
    }

    private fun stored(): Map<String, List<String>> =
        overrides.mapValues { (_, chords) -> chords.map(HollowKeyChord::toString) } + unknown

    private fun save() {
        try {
            file.createParentDirectories()
            file.writeText(json.encodeToString(format, stored()))
        } catch (e: Exception) {
            LOGGER.warn("Could not write the keymap", e)
        }
    }
}
