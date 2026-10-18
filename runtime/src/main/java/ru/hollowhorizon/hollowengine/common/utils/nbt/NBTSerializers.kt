package ru.hollowhorizon.hollowengine.common.utils.nbt

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.nbt.*
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.monster.Zombie
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3d
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.common.utils.literal
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.readItem
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.common.utils.serialization.registries
import java.util.*

object ForBlockPos : KSerializer<BlockPos> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BlockPos", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: BlockPos) = encoder.encodeLong(value.asLong())
    override fun deserialize(decoder: Decoder): BlockPos = BlockPos.of(decoder.decodeLong())
}

object ForResourceLocation : KSerializer<ResourceLocation> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Identifier", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ResourceLocation) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): ResourceLocation = decoder.decodeString().rl
}

object ForByteNBT : KSerializer<ByteTag> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ByteNBT", PrimitiveKind.BYTE)
    override fun serialize(encoder: Encoder, value: ByteTag) = encoder.encodeByte(value.asByte)
    override fun deserialize(decoder: Decoder): ByteTag = ByteTag.valueOf(decoder.decodeByte())
}

object ForShortNBT : KSerializer<ShortTag> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ShortNBT", PrimitiveKind.SHORT)
    override fun serialize(encoder: Encoder, value: ShortTag) = encoder.encodeShort(value.asShort)
    override fun deserialize(decoder: Decoder): ShortTag = ShortTag.valueOf(decoder.decodeShort())
}

object ForIntNBT : KSerializer<IntTag> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("IntNBT", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: IntTag) = encoder.encodeInt(value.asInt)
    override fun deserialize(decoder: Decoder): IntTag = IntTag.valueOf(decoder.decodeInt())
}

object ForLongNBT : KSerializer<LongTag> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LongNBT", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: LongTag) = encoder.encodeLong(value.asLong)
    override fun deserialize(decoder: Decoder): LongTag = LongTag.valueOf(decoder.decodeLong())
}

object ForFloatNBT : KSerializer<FloatTag> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FloatNBT", PrimitiveKind.FLOAT)
    override fun serialize(encoder: Encoder, value: FloatTag) = encoder.encodeFloat(value.asFloat)
    override fun deserialize(decoder: Decoder): FloatTag = FloatTag.valueOf(decoder.decodeFloat())
}

object ForDoubleNBT : KSerializer<DoubleTag> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("DoubleNBT", PrimitiveKind.DOUBLE)
    override fun serialize(encoder: Encoder, value: DoubleTag) = encoder.encodeDouble(value.asDouble)
    override fun deserialize(decoder: Decoder): DoubleTag = DoubleTag.valueOf(decoder.decodeDouble())
}

object ForStringNBT : KSerializer<StringTag> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StringNBT", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: StringTag) = encoder.encodeString(value.asString)
    override fun deserialize(decoder: Decoder): StringTag = StringTag.valueOf(decoder.decodeString())
}

object ForTextComponent : KSerializer<Component> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StringNBT", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Component) =
        encoder.encodeString(Component.Serializer.toJson(value, encoder.registries))

    override fun deserialize(decoder: Decoder) =
        Component.Serializer.fromJson(decoder.decodeString(), decoder.registries) ?: "".literal

}

object ForNbtNull : KSerializer<EndTag> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EndNBT", PrimitiveKind.BYTE)
    override fun serialize(encoder: Encoder, value: EndTag) = encoder.encodeByte(0)
    override fun deserialize(decoder: Decoder): EndTag = EndTag.INSTANCE.also { decoder.decodeByte() }
}

object ForByteArrayNBT : KSerializer<ByteArrayTag> {
    override val descriptor: SerialDescriptor = PublicisedListLikeDescriptorImpl(ForByteNBT.descriptor, "ByteArrayNBT")

    override fun serialize(encoder: Encoder, value: ByteArrayTag) = ListSerializer(ForByteNBT).serialize(encoder, value)

    override fun deserialize(decoder: Decoder): ByteArrayTag =
        ByteArrayTag(ListSerializer(ForByteNBT).deserialize(decoder).map { it.asByte })
}

object ForIntArrayNBT : KSerializer<IntArrayTag> {
    override val descriptor: SerialDescriptor = PublicisedListLikeDescriptorImpl(ForIntNBT.descriptor, "IntArrayNBT")

    override fun serialize(encoder: Encoder, value: IntArrayTag) = ListSerializer(ForIntNBT).serialize(encoder, value)

    override fun deserialize(decoder: Decoder): IntArrayTag =
        IntArrayTag(ListSerializer(ForIntNBT).deserialize(decoder).map { it.asInt })
}

object ForMatrix4f : KSerializer<Matrix4f> {
    override val descriptor: SerialDescriptor = PublicisedListLikeDescriptorImpl(ForFloatNBT.descriptor, "Matrix4f")

    override fun serialize(encoder: Encoder, value: Matrix4f) = ListSerializer(ForFloatNBT).serialize(
        encoder, listOf(
            value.m00(),
            value.m01(),
            value.m02(),
            value.m03(),
            value.m10(),
            value.m11(),
            value.m12(),
            value.m13(),
            value.m20(),
            value.m21(),
            value.m22(),
            value.m23(),
            value.m30(),
            value.m31(),
            value.m32(),
            value.m33()
        ).map { FloatTag.valueOf(it) })

    override fun deserialize(decoder: Decoder): Matrix4f {
        val array = ListSerializer(ForFloatNBT).deserialize(decoder).map { it.asFloat }
        return Matrix4f(
            array[0],
            array[1],
            array[2],
            array[3],
            array[4],
            array[5],
            array[6],
            array[7],
            array[8],
            array[9],
            array[10],
            array[11],
            array[12],
            array[13],
            array[14],
            array[15]
        )
    }
}

object ForLongArrayNBT : KSerializer<LongArrayTag> {
    override val descriptor: SerialDescriptor = PublicisedListLikeDescriptorImpl(ForLongNBT.descriptor, "LongArrayNBT")

    override fun serialize(encoder: Encoder, value: LongArrayTag) = ListSerializer(ForLongNBT).serialize(encoder, value)

    override fun deserialize(decoder: Decoder): LongArrayTag =
        LongArrayTag(ListSerializer(ForLongNBT).deserialize(decoder).map { it.asLong })
}

@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
object ForTag : KSerializer<Tag> {
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor("kotlinx.serialization.Polymorphic", PolymorphicKind.OPEN) {
            element("type", String.serializer().descriptor)
            element(
                "value", buildSerialDescriptor(
                    "kotlinx.serialization.Polymorphic<${Tag::class.simpleName}>", SerialKind.CONTEXTUAL
                )
            )
        }

    override fun serialize(encoder: Encoder, value: Tag) {
        if (encoder is ICanEncodeTag) encoder.encodeTag(value)
        else PolymorphicSerializer(Tag::class).serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): Tag {
        return if (decoder is ICanDecodeTag) decoder.decodeTag()
        else PolymorphicSerializer(Tag::class).deserialize(decoder)
    }
}

object ForNbtList : KSerializer<ListTag> {
    override val descriptor: SerialDescriptor = PublicisedListLikeDescriptorImpl(ForTag.descriptor, "ListTag")

    override fun serialize(encoder: Encoder, value: ListTag) {
        ListSerializer(ForTag).serialize(encoder, value)
    }

    override fun deserialize(decoder: Decoder): ListTag = ListTag().apply {
        for (tag in ListSerializer(ForTag).deserialize(decoder)) {
            add(tag)
        }
    }
}

object ForCompoundNBT : KSerializer<CompoundTag> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("NbtCompound") {
        mapSerialDescriptor(PrimitiveSerialDescriptor("Key", PrimitiveKind.STRING), ForTag.descriptor)
    }

    override fun serialize(encoder: Encoder, value: CompoundTag) {
        if (encoder is ICanEncodeCompoundNBT) {
            encoder.encodeCompoundNBT(value)
        } else {
            MapSerializer(String.serializer(), ForTag).serialize(
                encoder, value.allKeys.associateWith { value.get(it)!! })
        }

    }

    override fun deserialize(decoder: Decoder): CompoundTag {
        if (decoder is ICanDecodeCompoundNBT) {
            return decoder.decodeCompoundNBT()
        }
        return CompoundTag().apply {
            for ((key, value) in MapSerializer(String.serializer(), ForTag).deserialize(decoder)) {
                put(key, value)
            }
        }
    }
}

object ForItemStack : KSerializer<ItemStack> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ItemStack") {
        element("tag", ForCompoundNBT.descriptor)
    }

    override fun serialize(encoder: Encoder, value: ItemStack) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(
                descriptor,
                0,
                ForTag,
                if (value.isEmpty) CompoundTag() // ЕndTag не используется поскольку ListTag не поддерживает полиморфизм для Tag, из-за этого будут проблемы при сохранении
                else value.save(encoder.registries)
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): ItemStack {
        val dec = decoder.beginStructure(descriptor)

        var tag: Tag? = null
        if (dec.decodeSequentially()) {
            tag = dec.decodeSerializableElement(descriptor, 0, ForTag)
        } else {
            loop@ while (true) {
                when (val i = dec.decodeElementIndex(descriptor)) {
                    0 -> tag = dec.decodeSerializableElement(descriptor, i, ForTag)
                    CompositeDecoder.DECODE_DONE -> break@loop
                    else -> throw SerializationException("Unexpected index: $i")
                }
            }
        }
        dec.endStructure(descriptor)
        return if (tag !is CompoundTag || tag.isEmpty) ItemStack.EMPTY
        else tag.readItem(decoder.registries)
    }
}

object ForUuid : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Uuid") {
        element("most", Long.serializer().descriptor)
        element("least", Long.serializer().descriptor)
    }

    private const val MostIndex = 0
    private const val LeastIndex = 1

    override fun serialize(encoder: Encoder, value: UUID) {
        val compositeOutput = encoder.beginStructure(descriptor)
        compositeOutput.encodeLongElement(descriptor, MostIndex, value.mostSignificantBits)
        compositeOutput.encodeLongElement(descriptor, LeastIndex, value.leastSignificantBits)
        compositeOutput.endStructure(descriptor)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): UUID {
        val dec: CompositeDecoder = decoder.beginStructure(descriptor)

        val index = dec.decodeElementIndex(descriptor)
        return if (dec.decodeSequentially()) {
            val most = dec.decodeLongElement(descriptor, MostIndex)
            val least = dec.decodeLongElement(descriptor, LeastIndex)

            dec.endStructure(descriptor)
            UUID(most, least)
        } else {
            handleUnorthodoxInputOrdering(index, dec)
        }
    }

    private fun handleUnorthodoxInputOrdering(index: Int, dec: CompositeDecoder): UUID {
        var most: Long? = null // consider using flags or bit mask if you
        var least: Long? = null // need to read nullable non-optional properties
        when (index) {
            CompositeDecoder.DECODE_DONE -> throw SerializationException("Read should not be done yet.")
            MostIndex -> most = dec.decodeLongElement(descriptor, index)
            LeastIndex -> least = dec.decodeLongElement(descriptor, index)
            else -> throw SerializationException("Unknown index $index")
        }

        loop@ while (true) {
            when (val i = dec.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@loop
                MostIndex -> most = dec.decodeLongElement(descriptor, i)
                LeastIndex -> least = dec.decodeLongElement(descriptor, i)
                else -> throw SerializationException("Unknown index $i")
            }
        }
        dec.endStructure(descriptor)
        return UUID(most ?: missingField("most", "UUID") { 0L }, least ?: missingField("least", "UUID") { 0L })
    }
}

object ForVector3d : KSerializer<Vector3d> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Vector3d") {
        element("x", Double.serializer().descriptor)
        element("y", Double.serializer().descriptor)
        element("z", Double.serializer().descriptor)
    }


    private const val XIndex = 0
    private const val YIndex = 1
    private const val ZIndex = 2

    override fun serialize(encoder: Encoder, value: Vector3d) {
        val compositeOutput = encoder.beginStructure(descriptor)
        compositeOutput.encodeDoubleElement(descriptor, XIndex, value.x)
        compositeOutput.encodeDoubleElement(descriptor, YIndex, value.y)
        compositeOutput.encodeDoubleElement(descriptor, ZIndex, value.z)
        compositeOutput.endStructure(descriptor)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): Vector3d {
        val dec: CompositeDecoder = decoder.beginStructure(descriptor)

        var x = 0.0
        var y = 0.0
        var z = 0.0
        var xExists = false
        var yExists = false
        var zExists = false
        if (dec.decodeSequentially()) {
            x = dec.decodeDoubleElement(descriptor, XIndex)
            y = dec.decodeDoubleElement(descriptor, YIndex)
            z = dec.decodeDoubleElement(descriptor, ZIndex)
            xExists = true
            yExists = true
            zExists = true
        } else {
            loop@ while (true) {
                when (val i = dec.decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break@loop
                    XIndex -> {
                        x = dec.decodeDoubleElement(descriptor, i)
                        xExists = true
                    }

                    YIndex -> {
                        y = dec.decodeDoubleElement(descriptor, i)
                        yExists = true
                    }

                    ZIndex -> {
                        z = dec.decodeDoubleElement(descriptor, i)
                        zExists = true
                    }

                    else -> throw SerializationException("Unknown index $i")
                }
            }
        }

        dec.endStructure(descriptor)
        if (!xExists) x = missingField("x", "Vec3d") { 0.0 }
        if (!yExists) y = missingField("y", "Vec3d") { 0.0 }
        if (!zExists) z = missingField("z", "Vec3d") { 0.0 }

        return Vector3d(x, y, z)
    }
}

object ForVec3 : KSerializer<Vec3> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Vector3d") {
        element("x", Double.serializer().descriptor)
        element("y", Double.serializer().descriptor)
        element("z", Double.serializer().descriptor)
    }

    private const val XIndex = 0
    private const val YIndex = 1
    private const val ZIndex = 2

    override fun serialize(encoder: Encoder, value: Vec3) {
        val compositeOutput = encoder.beginStructure(descriptor)
        compositeOutput.encodeDoubleElement(descriptor, XIndex, value.x)
        compositeOutput.encodeDoubleElement(descriptor, YIndex, value.y)
        compositeOutput.encodeDoubleElement(descriptor, ZIndex, value.z)
        compositeOutput.endStructure(descriptor)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): Vec3 {
        val dec: CompositeDecoder = decoder.beginStructure(descriptor)

        var x = 0.0
        var y = 0.0
        var z = 0.0
        var xExists = false
        var yExists = false
        var zExists = false
        if (dec.decodeSequentially()) {
            x = dec.decodeDoubleElement(descriptor, XIndex)
            y = dec.decodeDoubleElement(descriptor, YIndex)
            z = dec.decodeDoubleElement(descriptor, ZIndex)
            xExists = true
            yExists = true
            zExists = true
        } else {
            loop@ while (true) {
                when (val i = dec.decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break@loop
                    XIndex -> {
                        x = dec.decodeDoubleElement(descriptor, i)
                        xExists = true
                    }

                    YIndex -> {
                        y = dec.decodeDoubleElement(descriptor, i)
                        yExists = true
                    }

                    ZIndex -> {
                        z = dec.decodeDoubleElement(descriptor, i)
                        zExists = true
                    }

                    else -> throw SerializationException("Unknown index $i")
                }
            }
        }

        dec.endStructure(descriptor)
        if (!xExists) x = missingField("x", "Vec3d") { 0.0 }
        if (!yExists) y = missingField("y", "Vec3d") { 0.0 }
        if (!zExists) z = missingField("z", "Vec3d") { 0.0 }

        return Vec3(x, y, z)
    }
}

object ForVec3f : KSerializer<Vec3f> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Vec3f") {
        element("x", Float.serializer().descriptor)
        element("y", Float.serializer().descriptor)
        element("z", Float.serializer().descriptor)
    }

    override fun serialize(encoder: Encoder, value: Vec3f) {
        val compositeOutput = encoder.beginStructure(descriptor)
        compositeOutput.encodeFloatElement(descriptor, 0, value.x)
        compositeOutput.encodeFloatElement(descriptor, 1, value.y)
        compositeOutput.encodeFloatElement(descriptor, 2, value.z)
        compositeOutput.endStructure(descriptor)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): Vec3f {
        val dec: CompositeDecoder = decoder.beginStructure(descriptor)

        var x = 0.0f
        var y = 0.0f
        var z = 0.0f
        var xExists = false
        var yExists = false
        var zExists = false
        if (dec.decodeSequentially()) {
            x = dec.decodeFloatElement(descriptor, 0)
            y = dec.decodeFloatElement(descriptor, 1)
            z = dec.decodeFloatElement(descriptor, 2)
            xExists = true
            yExists = true
            zExists = true
        } else {
            loop@ while (true) {
                when (val i = dec.decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break@loop
                    0 -> {
                        x = dec.decodeFloatElement(descriptor, i)
                        xExists = true
                    }

                    1 -> {
                        y = dec.decodeFloatElement(descriptor, i)
                        yExists = true
                    }

                    2 -> {
                        z = dec.decodeFloatElement(descriptor, i)
                        zExists = true
                    }

                    else -> throw SerializationException("Unknown index $i")
                }
            }
        }

        dec.endStructure(descriptor)
        if (!xExists) x = missingField("x", "Vec3f") { 0.0f }
        if (!yExists) y = missingField("y", "Vec3f") { 0.0f }
        if (!zExists) z = missingField("z", "Vec3f") { 0.0f }

        return Vec3f(x, y, z)
    }
}

object ForQuatF : KSerializer<QuatF> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("QuatF") {
        element("x", Float.serializer().descriptor)
        element("y", Float.serializer().descriptor)
        element("z", Float.serializer().descriptor)
        element("w", Float.serializer().descriptor)
    }

    override fun serialize(encoder: Encoder, value: QuatF) {
        val compositeOutput = encoder.beginStructure(descriptor)
        compositeOutput.encodeFloatElement(descriptor, 0, value.x)
        compositeOutput.encodeFloatElement(descriptor, 1, value.y)
        compositeOutput.encodeFloatElement(descriptor, 2, value.z)
        compositeOutput.encodeFloatElement(descriptor, 3, value.w)
        compositeOutput.endStructure(descriptor)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): QuatF {
        val dec = decoder.beginStructure(descriptor)

        var x = 0f
        var y = 0f
        var z = 0f
        var w = 1f
        var xExists = false
        var yExists = false
        var zExists = false
        var wExists = false
        if (dec.decodeSequentially()) {
            x = dec.decodeFloatElement(descriptor, 0)
            y = dec.decodeFloatElement(descriptor, 1)
            z = dec.decodeFloatElement(descriptor, 2)
            w = dec.decodeFloatElement(descriptor, 3)
            xExists = true
            yExists = true
            zExists = true
            wExists = true
        } else {
            loop@ while (true) {
                when (val i = dec.decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break@loop
                    0 -> {
                        x = dec.decodeFloatElement(descriptor, i)
                        xExists = true
                    }

                    1 -> {
                        y = dec.decodeFloatElement(descriptor, i)
                        yExists = true
                    }

                    2 -> {
                        z = dec.decodeFloatElement(descriptor, i)
                        zExists = true
                    }

                    3 -> {
                        w = dec.decodeFloatElement(descriptor, i)
                        wExists = true
                    }

                    else -> throw SerializationException("Unknown index $i")
                }
            }
        }

        dec.endStructure(descriptor)
        if (!xExists) x = missingField("x", "QuatF") { 0f }
        if (!yExists) y = missingField("y", "QuatF") { 0f }
        if (!zExists) z = missingField("z", "QuatF") { 0f }
        if (!wExists) w = missingField("w", "QuatF") { 1f }

        return QuatF(x, y, z, w)
    }
}

object ForVector3f : KSerializer<Vector3f> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Vector3f") {
        element("x", Float.serializer().descriptor)
        element("y", Float.serializer().descriptor)
        element("z", Float.serializer().descriptor)
    }
    private const val XIndex = 0
    private const val YIndex = 1
    private const val ZIndex = 2

    override fun serialize(encoder: Encoder, value: Vector3f) {
        val compositeOutput = encoder.beginStructure(descriptor)
        compositeOutput.encodeFloatElement(descriptor, XIndex, value.x())
        compositeOutput.encodeFloatElement(descriptor, YIndex, value.y())
        compositeOutput.encodeFloatElement(descriptor, ZIndex, value.z())
        compositeOutput.endStructure(descriptor)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): Vector3f {
        val dec: CompositeDecoder = decoder.beginStructure(descriptor)

        var x = 0.0f
        var y = 0.0f
        var z = 0.0f
        var xExists = false
        var yExists = false
        var zExists = false
        if (dec.decodeSequentially()) {
            x = dec.decodeFloatElement(descriptor, XIndex)
            y = dec.decodeFloatElement(descriptor, YIndex)
            z = dec.decodeFloatElement(descriptor, ZIndex)
            xExists = true
            yExists = true
            zExists = true
        } else {
            loop@ while (true) {
                when (val i = dec.decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break@loop
                    XIndex -> {
                        x = dec.decodeFloatElement(descriptor, i)
                        xExists = true
                    }

                    YIndex -> {
                        y = dec.decodeFloatElement(descriptor, i)
                        yExists = true
                    }

                    ZIndex -> {
                        z = dec.decodeFloatElement(descriptor, i)
                        zExists = true
                    }

                    else -> throw SerializationException("Unknown index $i")
                }
            }
        }

        dec.endStructure(descriptor)
        if (!xExists) x = missingField("x", "Vector3f") { 0.0f }
        if (!yExists) y = missingField("y", "Vector3f") { 0.0f }
        if (!zExists) z = missingField("z", "Vector3f") { 0.0f }

        return Vector3f(x, y, z)
    }
}

object ForEntity : KSerializer<Entity> {
    override val descriptor = PrimitiveSerialDescriptor("entityId", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Entity {
        return Minecraft.getInstance().level?.getEntity(decoder.decodeInt()) ?: Zombie(Minecraft.getInstance().level!!)
    }

    override fun serialize(encoder: Encoder, value: Entity) {
        encoder.encodeInt(value.id)
    }
}