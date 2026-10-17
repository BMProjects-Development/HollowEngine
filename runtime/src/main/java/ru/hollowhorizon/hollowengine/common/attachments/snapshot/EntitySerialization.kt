package ru.hollowhorizon.hollowengine.common.attachments.snapshot

import io.netty.buffer.Unpooled
import net.minecraft.nbt.Tag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.entity.Entity
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.api.Component
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.tracking.MCEntity
import ru.hollowhorizon.hollowengine.common.utils.bytebuf.ByteBufFormat
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.yaml.YamlFormat
import kotlin.reflect.KClass

object EntitySerialization {
    private val LOGGER: Logger = LogManager.getLogger(EntitySerialization::class.java)

    fun serializersModule() = ComponentDescriptorRegistry.serializersModule()
    fun nbtFormat() = NBTFormat(serializersModule())
    fun byteBufFormat() = ByteBufFormat(serializersModule())

    fun extract(entity: MCEntity): EntitySnapshot = snapshotOf(entity)
    fun apply(target: MCEntity, snapshot: EntitySnapshot) = applySnapshot(target, snapshot)

    fun serializeToNbt(snapshot: EntitySnapshot): Tag = nbtFormat().serialize(EntitySnapshot.serializer(), snapshot)
    fun deserializeFromNbt(tag: Tag): EntitySnapshot = nbtFormat().deserialize(EntitySnapshot.serializer(), tag)
    fun tryDeserializeFromNbt(tag: Tag, source: String = "NBT entity snapshot"): EntitySnapshot? =
        softDeserialize(source) { deserializeFromNbt(tag) }

    fun serializeToByteBuf(snapshot: EntitySnapshot, buf: FriendlyByteBuf = FriendlyByteBuf(Unpooled.buffer())): FriendlyByteBuf =
        byteBufFormat().serialize(EntitySnapshot.serializer(), snapshot, buf)
    fun deserializeFromByteBuf(buf: FriendlyByteBuf): EntitySnapshot =
        byteBufFormat().deserialize(EntitySnapshot.serializer(), buf)
    fun tryDeserializeFromByteBuf(buf: FriendlyByteBuf, source: String = "ByteBuf entity snapshot"): EntitySnapshot? =
        softDeserialize(source) { deserializeFromByteBuf(buf) }

    // Kept for backward compatibility with existing UI/files, now encoded as JSON text.
    fun serializeToYaml(snapshot: EntitySnapshot): String =
        YamlFormat.encodeToString(EntitySnapshot.serializer(), snapshot, serializersModule())
    fun deserializeFromYaml(text: String): EntitySnapshot =
        YamlFormat.decodeFromString(EntitySnapshot.serializer(), text, serializersModule())
    fun tryDeserializeFromYaml(text: String, source: String = "YAML entity snapshot"): EntitySnapshot? =
        softDeserialize(source) { deserializeFromYaml(text) }

    fun serializeEntityToNbt(entity: MCEntity): Tag = serializeToNbt(extract(entity))
    fun serializeEntityToByteBuf(entity: MCEntity, buf: FriendlyByteBuf = FriendlyByteBuf(Unpooled.buffer())): FriendlyByteBuf =
        serializeToByteBuf(extract(entity), buf)
    fun serializeEntityToYaml(entity: MCEntity): String = serializeToYaml(extract(entity))

    fun deserializeInto(target: MCEntity, tag: Tag): EntitySnapshot = deserializeFromNbt(tag).also { apply(target, it) }
    fun deserializeInto(target: MCEntity, buf: FriendlyByteBuf): EntitySnapshot = deserializeFromByteBuf(buf).also { apply(target, it) }
    fun deserializeInto(target: MCEntity, yaml: String): EntitySnapshot = deserializeFromYaml(yaml).also { apply(target, it) }

    fun tryDeserializeInto(target: MCEntity, tag: Tag, source: String = "NBT entity snapshot"): EntitySnapshot? =
        tryDeserializeFromNbt(tag, source)?.also { apply(target, it) }
    fun tryDeserializeInto(target: MCEntity, buf: FriendlyByteBuf, source: String = "ByteBuf entity snapshot"): EntitySnapshot? =
        tryDeserializeFromByteBuf(buf, source)?.also { apply(target, it) }
    fun tryDeserializeInto(target: MCEntity, yaml: String, source: String = "YAML entity snapshot"): EntitySnapshot? =
        tryDeserializeFromYaml(yaml, source)?.also { apply(target, it) }

    fun descriptorFor(component: Component) = ComponentDescriptorRegistry.descriptorOrNull(component::class)
    fun descriptorFor(type: KClass<*>) = ComponentDescriptorRegistry.descriptorOrNull(type)

    private inline fun softDeserialize(source: String, decode: () -> EntitySnapshot): EntitySnapshot? {
        return runCatching(decode).onFailure { throwable ->
            LOGGER.error("Failed to deserialize $source", throwable)
        }.getOrNull()
    }
}

/**
 * The entity's components as a snapshot. Reuses the snapshot cached alongside the components, so the
 * repeated calls behind every `sendTrackingEntityAndSelf` do not each copy the component list.
 */
fun snapshotOf(entity: Entity): EntitySnapshot =
    AttachmentRegistry.entitySnapshot(entity.level(), entity.uuid) ?: EntitySnapshot().withEntity(entity)

fun applySnapshot(target: Entity, snapshot: EntitySnapshot) {
    val desired = snapshot.componentById()
    val all = AttachmentRegistry.componentsById(target)
    all.keys.removeIf { it !in desired.keys }
    desired.forEach { (id, component) -> all[id] = component }
}
