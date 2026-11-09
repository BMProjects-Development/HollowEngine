package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.minecraft.nbt.CompoundTag

/**
 * A `var` of a stage that `@State` lambdas captured. The compiler turns the var into a box, so the stage and its
 * lambdas read and write one value, and the story saves that value once, with the stage that declared it.
 */
class StoryBox<T> internal constructor(internal val id: String, var value: T)

/** Where the compiler gets the boxes of shared vars from. */
object StoryBoxes {
    /**
     * The box of the var [name] the current stage declares, set to [initial]. The same declaration gets the same box
     * when its segment repeats after a restart, so lambdas started before it keep sharing the value.
     */
    @JvmStatic
    fun <T> box(name: String, serializer: KSerializer<Any?>, initial: T): StoryBox<T> {
        val run = StoryRun.current() ?: throw StoryException("var $name is shared with a @State lambda, which only works while a story runs")
        val frame = run.frames.lastOrNull() ?: throw StoryException("var $name is shared with a @State lambda outside a stage")
        return run.root.boxes.boxFor(frame, name, serializer, initial)
    }
}

/** The boxes of shared vars of one story, saved with its tree. */
internal class StoryBoxTable {
    private class Live(val box: StoryBox<Any?>, val serializer: KSerializer<Any?>)

    private val live = LinkedHashMap<String, Live>()
    private var saved = CompoundTag()
    private var next = 0L

    @Suppress("UNCHECKED_CAST")
    fun <T> boxFor(frame: StoryFrame, name: String, serializer: KSerializer<Any?>, initial: T): StoryBox<T> {
        val key = "box\$$name#${frame.nextBoxOrdinal(name)}"
        val id = frame.segment.getString(key).ifEmpty {
            "${frame.stage}.$name#${next++}".also { id ->
                frame.segment.putString(key, id)
                frame.boxes += id
            }
        }
        val box = live[id]?.box ?: StoryBox<Any?>(id, initial).also { live[id] = Live(it, serializer) }
        saved.remove(id)
        box.value = initial
        return box as StoryBox<T>
    }

    /** The box [id] a lambda or a restored local refers to. */
    fun resolve(id: String, serializer: KSerializer<Any?>): StoryBox<Any?> {
        live[id]?.let { return it.box }
        val tag = saved.get(id) ?: throw StoryException(
            "A @State lambda refers to a var that is gone: the stage that declared it returned, or it belongs to another story"
        )
        val box = StoryBox(id, StoryValues.decode(serializer, tag))
        live[id] = Live(box, serializer)
        saved.remove(id)
        return box
    }

    /** The stage that declared [ids] returned. */
    fun release(ids: Collection<String>) {
        ids.forEach { id ->
            live.remove(id)
            saved.remove(id)
        }
    }

    fun save(): CompoundTag = CompoundTag().apply {
        putLong("next", next)
        val values = saved.copy()
        live.forEach { (id, entry) -> values.put(id, StoryValues.encode(entry.serializer, entry.box.value)) }
        put("values", values)
    }

    /** Puts back what [save] wrote; boxes are made again as the code that uses them asks for them. */
    fun load(tag: CompoundTag) {
        live.clear()
        saved = tag.getCompound("values").copy()
        next = maxOf(next, tag.getLong("next"))
    }
}

/** A box is written as its id; reading it finds the box the story keeps under that id. */
internal class StoryBoxSerializer(private val value: KSerializer<Any?>) : KSerializer<StoryBox<Any?>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("story.box", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: StoryBox<Any?>) = encoder.encodeString(value.id)

    override fun deserialize(decoder: Decoder): StoryBox<Any?> {
        val id = decoder.decodeString()
        val run = StoryRun.current() ?: throw StoryException("A shared var is only read while its story runs")
        return run.root.boxes.resolve(id, value)
    }
}
