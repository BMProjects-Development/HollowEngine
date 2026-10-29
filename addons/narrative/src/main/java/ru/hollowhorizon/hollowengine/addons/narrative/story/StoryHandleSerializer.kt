package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** A handle is written as the path of its run, and read back as the run under that path in the current story. */
internal class StoryHandleSerializer(private val result: KSerializer<Any?>?) : KSerializer<StoryHandle<Any?>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("story.handle", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: StoryHandle<Any?>) = encoder.encodeString(value.run.path)

    override fun deserialize(decoder: Decoder): StoryHandle<Any?> {
        val path = decoder.decodeString()
        val current = StoryRun.current() ?: throw StoryException("A story handle is only read while a story runs")
        val run = current.runs.find(path) ?: StoryRun.ended(current.runs, path.substringAfterLast('/'))
        return StoryHandle(run, result)
    }
}
