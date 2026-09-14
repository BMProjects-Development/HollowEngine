package ru.hollowhorizon.hollowengine.common.scripting.startup

import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import ru.hollowhorizon.hollowengine.api.AutoModelType
import ru.hollowhorizon.hollowengine.api.RegistryHolder
import ru.hollowhorizon.hollowengine.common.registry.HollowRegistry

/**
 * Base of `*.startup.kts`. It runs once while the game starts, before registries freeze, on the client and
 * on a dedicated server alike: that is where items, blocks and other loads.
 */
abstract class StartupScript(val isClientSide: Boolean, namespace: String) : HollowRegistry(namespace) {
    /**
     * Registers a block together with the item that places it, under the same id. By default the block is
     * a cube textured with `textures/block/<id>.png` and the item looks like the block; with [model] set to
     * `null` both models are expected in resources instead.
     */
    inline fun <reified T : Block> block(
        id: String,
        model: AutoModelType? = AutoModelType.CUBE_ALL,
        itemProperties: Item.Properties = Item.Properties(),
        noinline create: () -> T,
    ): RegistryHolder<T> {
        val location = location(id)
        val block = register(location, model) { create() }
        val itemModel = model?.let { AutoModelType.custom("${location.namespace}:block/${location.path}") }
        register(location, itemModel) { BlockItem(block.get(), itemProperties) }
        return block
    }
}
