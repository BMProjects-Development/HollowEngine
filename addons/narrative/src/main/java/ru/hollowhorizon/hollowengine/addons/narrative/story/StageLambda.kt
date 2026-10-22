package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT

/**
 * A lambda, that compiler turned into a stage: written where a `@State suspend () -> T` is expected, its body
 * became the stage [stage] of the script [instance] and what it captured became [args].
 *
 * @param result reads the value the stage returns; only known where the lambda was written.
 */
class StageLambda<T>(
    val instance: String,
    val stage: String,
    val args: CompoundTag,
    internal val result: KSerializer<Any?>? = null,
) : suspend () -> T {
    @Suppress("UNCHECKED_CAST")
    override suspend fun invoke(): T = StoryInstances.invoke(instance, stage, args) as T

    /** The first frame of a run that starts with this lambda. */
    internal fun entry(run: StoryRun) = StoryFrame(run, instance, stage).apply { args = this@StageLambda.args.copy() }

    @Serializable
    private class Saved(val instance: String, val stage: String, @Serializable(with = ForCompoundNBT::class) val args: CompoundTag)

    internal object Serializer : KSerializer<StageLambda<*>> {
        override val descriptor: SerialDescriptor = SerialDescriptor("story.stage", Saved.serializer().descriptor)

        override fun serialize(encoder: Encoder, value: StageLambda<*>) =
            encoder.encodeSerializableValue(Saved.serializer(), Saved(value.instance, value.stage, value.args))

        override fun deserialize(decoder: Decoder): StageLambda<*> =
            decoder.decodeSerializableValue(Saved.serializer()).let { StageLambda<Any?>(it.instance, it.stage, it.args) }
    }
}

/** The value of a stage lambda, failing clearly when something else was passed where one was expected. */
internal fun (suspend () -> Any?).asStage(where: String): StageLambda<*> = this as? StageLambda<*>
    ?: throw StoryException("$where takes a lambda written in place, not a function value")
