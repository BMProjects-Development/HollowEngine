package ru.hollowhorizon.hollowengine.common.scripting.state

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.StringTag
import ru.hollowhorizon.hollowengine.common.data.DataKey
import ru.hollowhorizon.hollowengine.common.data.read
import ru.hollowhorizon.hollowengine.common.data.write
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat

internal suspend fun stateContext(): StateContext {
    return currentCoroutineContext()[StateContext.Key] ?: error("StateContext not found!")
}

internal suspend fun transition(target: String) {
    stateContext().nextState = target
}

suspend fun stateTag(): CompoundTag = stateContext().tag

suspend fun runOnce(name: String, block: suspend () -> Unit) {
    val tag = stateTag()
    val list = tag.getList("once_tasks$", 8)
    val hasEntry = list.map { (it as StringTag).asString }.contains(name)
    if (hasEntry) return

    block()

    list.add(StringTag.valueOf(name))
    tag.put("once_tasks$", list)
}

@OptIn(InternalSerializationApi::class)
suspend inline fun <reified T : Any> remember(name: String, block: suspend () -> T): T {
    val tag = stateTag()

    val serializer = T::class.serializer()

    tag.get(name)?.let {
        return NBTFormat.deserialize(serializer, it)
    }

    val value = block()
    tag.put(name, NBTFormat.serialize(serializer, value))
    return value
}

suspend fun forget(name: String): Boolean {
    val tag = stateTag()
    val exists = tag.contains(name)
    tag.remove(name)
    return exists
}

suspend fun <T : Any> stateGet(key: DataKey<T>): T? =
    stateTag().read(key) ?: key.defaultValue?.invoke()

suspend fun <T : Any> stateGetOrPut(key: DataKey<T>, defaultValue: () -> T): T {
    val tag = stateTag()
    tag.read(key)?.let { return it }
    return defaultValue().also { tag.write(key, it) }
}

suspend fun <T : Any> stateGetOrPut(key: DataKey<T>): T =
    stateGetOrPut(key, key.defaultValue ?: error("Data key '${key.name}' has no default value"))

suspend fun <T : Any> stateSet(key: DataKey<T>, value: T) {
    stateTag().write(key, value)
}

suspend fun <T : Any> stateUpdate(key: DataKey<T>, transform: (T) -> T): T {
    val current = stateGet(key) ?: error("Data key '${key.name}' is not set and has no default value")
    return transform(current).also { stateSet(key, it) }
}

suspend fun stateContains(key: DataKey<*>): Boolean = stateTag().contains(key.name)

suspend fun stateRemove(key: DataKey<*>): Boolean {
    val tag = stateTag()
    val existed = tag.contains(key.name)
    tag.remove(key.name)
    return existed
}
