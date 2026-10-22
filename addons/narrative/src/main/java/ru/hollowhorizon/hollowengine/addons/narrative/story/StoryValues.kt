package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.*
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryValues.put
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.npcs.registry.NpcRef
import ru.hollowhorizon.hollowengine.common.npcs.registry.npcs
import ru.hollowhorizon.hollowengine.common.utils.currentServerOrNull
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Turns the values a story keeps (stage arguments, results, locals, saved variables) into NBT and back.
 *
 * Everything [NBTFormat] can write is accepted. Entities, at any depth of a list, set, map, pair or triple,
 * are written as references and found again when read.
 */
object StoryValues {
    private val serializers = HashMap<KType, KSerializer<Any?>>()

    /** Finds the entity a reference names, or null when it is not around right now. */
    var entities: (UUID, KClass<out Entity>) -> Entity? = ::findOnServer

    @Suppress("UNCHECKED_CAST")
    fun serializer(type: KType): KSerializer<Any?> = serializers.getOrPut(type) {
        val classifier =
            type.classifier as? KClass<*> ?: throw StoryException("A story cannot keep a value of type $type")
        val arguments by lazy {
            type.arguments.map { argument ->
                argument.type?.let(::serializer) ?: throw StoryException("A story cannot keep a value of type $type")
            }
        }
        val base: KSerializer<*> = when {
            Entity::class.java.isAssignableFrom(classifier.java) -> EntityReference(classifier as KClass<out Entity>)
            classifier == NpcRef::class -> NpcReference
            classifier.isFunction() -> StageLambda.Serializer
            classifier == List::class || classifier == Collection::class -> ListSerializer(arguments[0])
            classifier == Set::class -> SetSerializer(arguments[0])
            classifier == Map::class -> MapSerializer(arguments[0], arguments[1])
            classifier == Pair::class -> PairSerializer(arguments[0], arguments[1])
            classifier == Triple::class -> TripleSerializer(arguments[0], arguments[1], arguments[2])
            else -> runCatching { NBTFormat.serializersModule.serializer(type) }.getOrElse {
                throw StoryException("A story cannot keep a value of type $type: it is not serializable", it)
            }
        }
        (if (type.isMarkedNullable) (base as KSerializer<Any>).nullable else base) as KSerializer<Any?>
    }

    /** Writes [value]; a top-level null gets a marker of its own, which the NBT format has no reliable form for. */
    fun encode(serializer: KSerializer<Any?>, value: Any?): Tag =
        if (value == null) NULL.copy() else NBTFormat.serialize(serializer, value)

    fun decode(serializer: KSerializer<Any?>, tag: Tag): Any? =
        if (tag == NULL) null else NBTFormat.deserialize(serializer, tag)

    private val NULL = CompoundTag().apply { putBoolean("story\$null", true) }

    /** Writes [value] into [tag] under [name]. */
    @JvmStatic
    fun put(tag: CompoundTag, name: String, serializer: KSerializer<Any?>, value: Any?) {
        tag.put(name, encode(serializer, value))
    }

    /** Reads what [put] wrote under [name]. */
    @JvmStatic
    fun get(tag: CompoundTag, name: String, serializer: KSerializer<Any?>): Any? {
        val value = tag.get(name) ?: throw StoryException("The saved story has no value '$name'")
        return decode(serializer, value)
    }

    private fun KClass<*>.isFunction(): Boolean = this == StageLambda::class || qualifiedName.orEmpty().let {
        it.startsWith("kotlin.Function") || it.startsWith("kotlin.coroutines.SuspendFunction")
    } || Function::class.java.isAssignableFrom(java)

    private fun findOnServer(uuid: UUID, type: KClass<out Entity>): Entity? {
        val server = currentServerOrNull() ?: return null
        if (NpcEntity::class.java.isAssignableFrom(type.java)) server.npcs.resolve(uuid)?.let { return it }
        return server.findEntity(uuid)
    }

    private fun MinecraftServer.findEntity(uuid: UUID): Entity? =
        playerList.getPlayer(uuid) ?: allLevels.firstNotNullOfOrNull { it.getEntity(uuid) }

    private class EntityReference(private val type: KClass<out Entity>) : KSerializer<Entity> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("story.entity", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Entity) = encoder.encodeString(value.uuid.toString())

        override fun deserialize(decoder: Decoder): Entity {
            val uuid = UUID.fromString(decoder.decodeString())
            val entity = entities(uuid, type) ?: throw StoryEntityMissing(uuid)
            if (!type.isInstance(entity)) {
                throw StoryException("Entity $uuid is a ${entity.javaClass.simpleName}, not a ${type.simpleName}")
            }
            return entity
        }
    }

    private object NpcReference : KSerializer<NpcRef> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("story.npc", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: NpcRef) = encoder.encodeString(value.uuid.toString())

        override fun deserialize(decoder: Decoder): NpcRef = NpcRef(UUID.fromString(decoder.decodeString()))
    }
}

/** The serializer a story keeps a [T] with. The compiler calls it for every value a stage saves. */
inline fun <reified T> storySerializer(): KSerializer<Any?> = StoryValues.serializer(typeOf<T>())

/** A saved story names an entity that is not loaded, so the run waits until it is. */
class StoryEntityMissing(val uuid: UUID) : RuntimeException("Entity $uuid is not loaded")
