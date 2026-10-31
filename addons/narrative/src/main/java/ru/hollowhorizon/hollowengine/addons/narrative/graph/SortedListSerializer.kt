package ru.hollowhorizon.hollowengine.addons.narrative.graph

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Writes a list in the order of [comparator], so that the same content is always the same text. */
class SortedListSerializer<T>(
    elementSerializer: KSerializer<T>,
    private val comparator: Comparator<T>,
) : KSerializer<List<T>> {
    private val delegate = ListSerializer(elementSerializer)
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: List<T>) = delegate.serialize(encoder, value.sortedWith(comparator))

    override fun deserialize(decoder: Decoder): List<T> = delegate.deserialize(decoder)
}

object SortedVariablesSerializer : KSerializer<List<StoryVariable>> by SortedListSerializer(
    StoryVariable.serializer(),
    compareBy { it.name },
)

object SortedNodesSerializer : KSerializer<List<StoryNode>> by SortedListSerializer(
    StoryNode.serializer(),
    compareBy { it.id },
)

object SortedLinksSerializer : KSerializer<List<StoryLink>> by SortedListSerializer(
    StoryLink.serializer(),
    compareBy({ it.from }, { it.fromPin }, { it.to }, { it.toPin }),
)
