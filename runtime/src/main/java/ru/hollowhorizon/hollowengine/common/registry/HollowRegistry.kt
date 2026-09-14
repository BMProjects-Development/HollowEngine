@file:Suppress("UNCHECKED_CAST")
package ru.hollowhorizon.hollowengine.common.registry

import net.minecraft.core.Registry
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Items
import ru.hollowhorizon.hollowengine.HollowEngine.MODID
import ru.hollowhorizon.hollowengine.api.AutoModelType
import ru.hollowhorizon.hollowengine.api.RegistryHolder
import ru.hollowhorizon.hollowengine.common.utils.HollowCreativeTab
import ru.hollowhorizon.hollowengine.common.utils.mcTranslate
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.reflect.KProperty

open class HollowRegistry(val modId: String = MODID) {
    /**
     * Avoid fake NotNulls parameters like BlockEntityType.Builder::build
     */
    fun <T> promise(): T = null as T

    inline fun <reified T : Any> register(
        location: ResourceLocation,
        autoModel: AutoModelType? = AutoModelType.DEFAULT,
        registry: Registry<in T>? = null,
        noinline registryEntry: (ResourceLocation) -> T,
    ): RegistryHolder<T> {
        return CommonRegistryProvider.register(
            location,
            registry as Registry<Any>?,
            autoModel,
            { registryEntry(location) },
            T::class.java as Class<Any>
        ) as RegistryHolder<T>
    }

    inline fun <reified T : Any> register(
        id: String,
        autoModel: AutoModelType? = AutoModelType.DEFAULT,
        registry: Registry<in T>? = null,
        noinline registryEntry: (ResourceLocation) -> T,
    ): RegistryHolder<T> = register(location(id), autoModel, registry, registryEntry)

    /** A plain [id] lands in [modId]; a full `namespace:path` is used as written. */
    fun location(id: String): ResourceLocation = if (':' in id) id.rl else "$modId:$id".rl

    fun creativeTab(name: String, block: CreativeModeTab.Builder.() -> Unit = {}) = register(name) {
        HollowCreativeTab.builder()
            .icon { Items.DIRT.defaultInstance }
            .title("itemGroup.$name".mcTranslate)
            .apply { block() }
            .build()
    }
}

operator fun <T> RegistryHolder<T>.getValue(thisRef: Any?, property: KProperty<*>): T = get()
