package ru.hollowhorizon.hollowengine.common.utils

import com.mojang.blaze3d.systems.RenderSystem
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import net.minecraft.ChatFormatting
import net.minecraft.Util
import net.minecraft.core.HolderLookup
import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import ru.hollowhorizon.hollowengine.client.utils.clientRegistryAccess

/**
 * Checks if the game is running in a production environment.
 *
 * @return `true` if the game is in production mode, otherwise `false`.
 */
var isProduction: Boolean
    get() = RuntimeFlags.production
    set(value) {
        RuntimeFlags.production = value
    }

/**
 * Checks if the current thread is the logical client thread.
 *
 * @return `true` if running on the logical client thread, otherwise `false`.
 */
val isLogicalClient get() = isPhysicalClient && RenderSystem.isOnRenderThread()

/**
 * Determines if the game is running on a physical client.
 *
 * @return `true` if running on the client side, otherwise `false`.
 */
var isPhysicalClient: Boolean
    get() = RuntimeFlags.physicalClient
    set(value) {
        RuntimeFlags.physicalClient = value
    }

val RANDOM: RandomSource
    get() = RandomSourceHolder.INSTANCE

private object RandomSourceHolder {
    val INSTANCE: RandomSource = RandomSource.create()
}

/**
 * Stores the current Minecraft server instance.
 */
@Volatile
private var currentServerReference: MinecraftServer? = null

var currentServer: MinecraftServer
    get() = currentServerReference ?: error("Minecraft server is not initialized")
    set(value) {
        currentServerReference = value
    }

fun currentServerOrNull(): MinecraftServer? = currentServerReference

fun clearCurrentServer(server: MinecraftServer) {
    if (currentServerReference === server) currentServerReference = null
}

/**
 * Registries of the side currently working with the data.
 */
val registryAccess: RegistryAccess
    get() = (if (isLogicalClient) clientRegistries() ?: serverRegistries() else serverRegistries() ?: clientRegistries())
        ?: error("No registries to serialize against: neither a server nor a client connection is running")

private fun serverRegistries() = currentServerOrNull()?.registryAccess()

private fun clientRegistries() = if (isPhysicalClient) clientRegistryAccess else null

/**
 * Converts a string to a Minecraft resource location.
 */
val String.rl: ResourceLocation
    get() =
        ResourceLocation.parse(this)

fun String.isValidRL(): Boolean {
    return ResourceLocation.tryParse(this) != null
}

val String.literal: MutableComponent get() = Component.literal(this)
val String.mcTranslate: MutableComponent get() = Component.translatable(this)
fun String.mcTranslate(vararg args: Any) = Component.translatable(this, *args)

/**
 * Appends one part to another.
 */
operator fun MutableComponent.plus(other: Component): MutableComponent = this.copy().append(other)
operator fun MutableComponent.plus(text: String): MutableComponent = this.copy().append(text)

// Additional helper methods for text formatting and interaction
fun MutableComponent.colored(color: Int): MutableComponent = this.withStyle { it.withColor(color) }
fun MutableComponent.colored(color: ChatFormatting): MutableComponent = this.withStyle { it.withColor(color) }
fun MutableComponent.bold(): MutableComponent = this.withStyle { it.withBold(true) }
fun MutableComponent.italic(): MutableComponent = this.withStyle { it.withItalic(true) }
fun MutableComponent.obfuscated(): MutableComponent = this.withStyle { it.withObfuscated(true) }
fun MutableComponent.underlined(): MutableComponent = this.withStyle { it.withUnderlined(true) }
fun MutableComponent.strikethrough(): MutableComponent = this.withStyle { it.withStrikethrough(true) }
fun MutableComponent.font(font: ResourceLocation) = this.withStyle { it.withFont(font) }
fun MutableComponent.onClickUrl(url: String): MutableComponent =
    this.withStyle { it.withClickEvent(ClickEvent(ClickEvent.Action.OPEN_URL, url)) }

fun MutableComponent.onClickCommand(command: String): MutableComponent =
    this.withStyle { it.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command)) }

fun MutableComponent.onClickSuggestion(command: String): MutableComponent =
    this.withStyle { it.withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command)) }

fun MutableComponent.onClickCopy(text: String): MutableComponent =
    this.withStyle { it.withClickEvent(ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text)) }

fun MutableComponent.onHoverText(text: Component): MutableComponent =
    this.withStyle { it.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, text)) }

fun MutableComponent.onHoverText(text: String) = onHoverText(Component.literal(text))
fun MutableComponent.onHoverItem(item: ItemStack) =
    this.withStyle { it.withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_ITEM, HoverEvent.ItemStackInfo(item))) }

fun MutableComponent.onHoverEntity(entity: Entity) = this.withStyle {
    it.withHoverEvent(
        HoverEvent(
            HoverEvent.Action.SHOW_ENTITY,
            HoverEvent.EntityTooltipInfo(entity.type, entity.uuid, entity.name)
        )
    )
}

fun openUrl(url: String) = Util.getPlatform().openUri(url)

/**
 * Memoizes a function, caching its results to improve performance.
 *
 * @return A memoized version of the function.
 */
fun <A, B> ((A) -> B).memoize(): (A) -> B {
    val cache: MutableMap<A, B> = Object2ObjectOpenHashMap()
    return {
        cache.getOrPut(it) { this(it) }
    }
}

/**
 * Saves an ItemStack to a CompoundTag.
 *
 * @return A CompoundTag representing the saved ItemStack.
 */
fun ItemStack.save() = save(registryAccess)

/**
 * Reads an ItemStack from a CompoundTag.
 *
 * @return An ItemStack instance loaded from the CompoundTag.
 */
fun CompoundTag.readItem(registries: HolderLookup.Provider = registryAccess) =
    if (isEmpty) ItemStack.EMPTY else ItemStack.parse(registries, this).orElseThrow()
