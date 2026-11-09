package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForCompoundNBT

/**
 * A lambda, that compiler turned into a stage: written where a `@State suspend (…) -> T` is expected, its body
 * became the stage [stage] of the script [instance] and what it captured became [args]. Its parameters, a
 * receiver included, become the following arguments of that stage, saved like any other.
 *
 * It is called like the lambda it replaced, and it can be saved like any other value. [StageLambda0] to
 * [StageLambda8] are its forms for each number of parameters.
 *
 * @param resultSerializer reads the stage result; recovered through its generated factory after a restart.
 */
abstract class StageLambda(
    val instance: String,
    val stage: String,
    val args: CompoundTag,
    private val resultSerializer: KSerializer<Any?>?,
    val arity: Int,
) {
    internal val result: KSerializer<Any?> get() = resultSerializer ?: StoryInstances.resultSerializer(instance, stage)

    /** Calls the stage with [parameters] after the captured arguments. */
    protected suspend fun call(vararg parameters: Any?): Any? = StoryInstances.call(instance, stage, args, parameters)

    /** The first frame of a run that starts with this lambda. */
    internal fun entry(run: StoryRun) = StoryFrame(run, instance, stage).apply { args = this@StageLambda.args.copy() }

    @Serializable
    private class Saved(
        val instance: String,
        val stage: String,
        @Serializable(with = ForCompoundNBT::class) val args: CompoundTag,
        val arity: Int,
    )

    internal object Serializer : KSerializer<StageLambda> {
        override val descriptor: SerialDescriptor = SerialDescriptor("story.stage", Saved.serializer().descriptor)

        override fun serialize(encoder: Encoder, value: StageLambda) =
            encoder.encodeSerializableValue(Saved.serializer(), Saved(value.instance, value.stage, value.args, value.arity))

        override fun deserialize(decoder: Decoder): StageLambda =
            decoder.decodeSerializableValue(Saved.serializer()).let { of(it.arity, it.instance, it.stage, it.args) }
    }

    internal companion object {
        /** The largest number of parameters, a receiver included, a stage lambda can take. */
        const val MAX_ARITY = 8

        fun of(arity: Int, instance: String, stage: String, args: CompoundTag): StageLambda = when (arity) {
            0 -> StageLambda0<Any?>(instance, stage, args)
            1 -> StageLambda1<Any?, Any?>(instance, stage, args)
            2 -> StageLambda2<Any?, Any?, Any?>(instance, stage, args)
            3 -> StageLambda3<Any?, Any?, Any?, Any?>(instance, stage, args)
            4 -> StageLambda4<Any?, Any?, Any?, Any?, Any?>(instance, stage, args)
            5 -> StageLambda5<Any?, Any?, Any?, Any?, Any?, Any?>(instance, stage, args)
            6 -> StageLambda6<Any?, Any?, Any?, Any?, Any?, Any?, Any?>(instance, stage, args)
            7 -> StageLambda7<Any?, Any?, Any?, Any?, Any?, Any?, Any?, Any?>(instance, stage, args)
            8 -> StageLambda8<Any?, Any?, Any?, Any?, Any?, Any?, Any?, Any?, Any?>(instance, stage, args)
            else -> throw StoryException("A stage lambda takes at most $MAX_ARITY parameters, not $arity")
        }
    }
}

@Suppress("UNCHECKED_CAST")
class StageLambda0<R>(instance: String, stage: String, args: CompoundTag, result: KSerializer<Any?>? = null) :
    StageLambda(instance, stage, args, result, 0), suspend () -> R {
    override suspend fun invoke(): R = call() as R
}

@Suppress("UNCHECKED_CAST")
class StageLambda1<P1, R>(instance: String, stage: String, args: CompoundTag, result: KSerializer<Any?>? = null) :
    StageLambda(instance, stage, args, result, 1), suspend (P1) -> R {
    override suspend fun invoke(p1: P1): R = call(p1) as R
}

@Suppress("UNCHECKED_CAST")
class StageLambda2<P1, P2, R>(instance: String, stage: String, args: CompoundTag, result: KSerializer<Any?>? = null) :
    StageLambda(instance, stage, args, result, 2), suspend (P1, P2) -> R {
    override suspend fun invoke(p1: P1, p2: P2): R = call(p1, p2) as R
}

@Suppress("UNCHECKED_CAST")
class StageLambda3<P1, P2, P3, R>(instance: String, stage: String, args: CompoundTag, result: KSerializer<Any?>? = null) :
    StageLambda(instance, stage, args, result, 3), suspend (P1, P2, P3) -> R {
    override suspend fun invoke(p1: P1, p2: P2, p3: P3): R = call(p1, p2, p3) as R
}

@Suppress("UNCHECKED_CAST")
class StageLambda4<P1, P2, P3, P4, R>(instance: String, stage: String, args: CompoundTag, result: KSerializer<Any?>? = null) :
    StageLambda(instance, stage, args, result, 4), suspend (P1, P2, P3, P4) -> R {
    override suspend fun invoke(p1: P1, p2: P2, p3: P3, p4: P4): R = call(p1, p2, p3, p4) as R
}

@Suppress("UNCHECKED_CAST")
class StageLambda5<P1, P2, P3, P4, P5, R>(instance: String, stage: String, args: CompoundTag, result: KSerializer<Any?>? = null) :
    StageLambda(instance, stage, args, result, 5), suspend (P1, P2, P3, P4, P5) -> R {
    override suspend fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5): R = call(p1, p2, p3, p4, p5) as R
}

@Suppress("UNCHECKED_CAST")
class StageLambda6<P1, P2, P3, P4, P5, P6, R>(instance: String, stage: String, args: CompoundTag, result: KSerializer<Any?>? = null) :
    StageLambda(instance, stage, args, result, 6), suspend (P1, P2, P3, P4, P5, P6) -> R {
    override suspend fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6): R = call(p1, p2, p3, p4, p5, p6) as R
}

@Suppress("UNCHECKED_CAST")
class StageLambda7<P1, P2, P3, P4, P5, P6, P7, R>(instance: String, stage: String, args: CompoundTag, result: KSerializer<Any?>? = null) :
    StageLambda(instance, stage, args, result, 7), suspend (P1, P2, P3, P4, P5, P6, P7) -> R {
    override suspend fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7): R = call(p1, p2, p3, p4, p5, p6, p7) as R
}

@Suppress("UNCHECKED_CAST")
class StageLambda8<P1, P2, P3, P4, P5, P6, P7, P8, R>(instance: String, stage: String, args: CompoundTag, result: KSerializer<Any?>? = null) :
    StageLambda(instance, stage, args, result, 8), suspend (P1, P2, P3, P4, P5, P6, P7, P8) -> R {
    override suspend fun invoke(p1: P1, p2: P2, p3: P3, p4: P4, p5: P5, p6: P6, p7: P7, p8: P8): R =
        call(p1, p2, p3, p4, p5, p6, p7, p8) as R
}

/** The value of a stage lambda, failing clearly when something else was passed where one was expected. */
internal fun Function<*>.asStage(where: String): StageLambda = this as? StageLambda
    ?: throw StoryException("$where takes a lambda written in place, not a function value")
