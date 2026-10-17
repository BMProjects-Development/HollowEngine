package ru.hollowhorizon.hollowengine.common.coroutines

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.events.entity.EntityLoadedEvent
import ru.hollowhorizon.hollowengine.common.events.entity.player.PlayerEvent
import ru.hollowhorizon.hollowengine.common.events.factory.await
import java.util.*

interface Ref<T> {
    val isLinkAlive: Boolean

    suspend fun resolve(): T

    suspend fun <V> read(value: T.() -> V) = resolve().value()
    suspend fun update(action: T.() -> Unit) = resolve().action()
}

context(server: MinecraftServer)
fun <T : Entity> entityRef(uuid: UUID, type: Class<T>): Ref<T> {
    return object : Ref<T> {
        override val isLinkAlive: Boolean
            get() = server.allLevels.any { type.isInstance(it.getEntity(uuid)) }

        override suspend fun resolve(): T {
            server.allLevels.forEach { level ->
                level.getEntity(uuid)?.let { entity -> return type.castEntity(uuid, entity) }
            }

            val entity = EntityLoadedEvent.await { it.entity.uuid == uuid }.entity
            return type.castEntity(uuid, entity)
        }
    }
}

context(server: MinecraftServer)
inline fun <reified T : Entity> entityRef(uuid: UUID): Ref<T> = entityRef(uuid, T::class.java)

context(server: MinecraftServer)
inline val <reified T : Entity> T.entityRef: Ref<T>
    get() = entityRef(uuid)

context(server: MinecraftServer)
fun playerRef(name: String) = object : Ref<ServerPlayer> {
    override val isLinkAlive: Boolean
        get() = server.playerList.getPlayerByName(name) != null

    override suspend fun resolve(): ServerPlayer {
        server.playerList.getPlayerByName(name)?.let { return it }

        return PlayerEvent.Join.await { it.player.gameProfile.name == name }.player as ServerPlayer
    }
}

context(server: MinecraftServer)
fun playerRef(uuid: UUID) = object : Ref<ServerPlayer> {
    override val isLinkAlive: Boolean
        get() = server.playerList.getPlayer(uuid) != null

    override suspend fun resolve(): ServerPlayer {
        server.playerList.getPlayer(uuid)?.let { return it }

        return PlayerEvent.Join.await { it.player.uuid == uuid }.player as ServerPlayer
    }
}

suspend fun <A, B, C> borrow(first: Ref<A>, second: Ref<B>, action: (first: A, second: B) -> C): C {
    while (true) {
        val f = first.resolve()
        val s = second.resolve()

        // За время получения второй сущности вторая могла устареть
        if (first.isLinkAlive && second.isLinkAlive) {
            return action(f, s)
        }
    }
}

suspend fun <A, B, C, D> borrow(
    first: Ref<A>,
    second: Ref<B>,
    third: Ref<C>,
    action: (first: A, second: B, third: C) -> D,
): D {
    while (true) {
        val f = first.resolve()
        val s = second.resolve()
        val t = third.resolve()

        // За время получения второй сущности вторая могла устареть
        if (first.isLinkAlive && second.isLinkAlive && third.isLinkAlive) {
            return action(f, s, t)
        }
    }
}

private fun <T : Entity> Class<T>.castEntity(uuid: UUID, entity: Entity): T {
    require(isInstance(entity)) {
        "Entity $uuid has type ${entity.javaClass.name}, expected $name"
    }
    return cast(entity)
}
