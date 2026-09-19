package ru.hollowhorizon.hollowengine.common.scripting

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.scripting.annotations.Import
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinScript
import ru.hollowhorizon.hollowengine.common.scripting.nodes.NodeScript
import ru.hollowhorizon.hollowengine.common.scripting.reload.ReloadScript
import ru.hollowhorizon.hollowengine.common.scripting.reload.ServerReloadContext
import ru.hollowhorizon.hollowengine.common.scripting.startup.StartupScript
import ru.hollowhorizon.hollowengine.common.scripting.ui.UiScript
import ru.hollowhorizon.hollowengine.common.scripting.ScriptClassProvider as Provider

const val NODE_SCRIPT_EXTENSION = "node.kts"
const val UI_SCRIPT_EXTENSION = "ui.kts"
const val RELOAD_SCRIPT_EXTENSION = "reload.kts"
const val STARTUP_SCRIPT_EXTENSION = "startup.kts"
const val CONSOLE_SCRIPT_EXTENSION = "console.kts"
const val MIXIN_SCRIPT_EXTENSION = "mixin.kts"
private const val CLIENT_RELOAD_CONTEXT = "ru.hollowhorizon.hollowengine.client.scripting.ClientReloadContext"
private const val CONSOLE_SCRIPT = "ru.hollowhorizon.hollowengine.client.scripting.ConsoleScript"

object DefaultScriptDefinitions {
    private val definitions by lazy(::createProviders)

    fun providerFor(fileName: String): Provider? =
        definitions.asSequence()
            .filter { fileName.endsWith(it.extension) }
            .maxByOrNull { it.extension.length }

    fun providers(): List<Provider> = definitions

    /**
     * What a script compiled while mixins are prepared may consist of: mixin scripts and the plain scripts
     * they import. Unlike the rest, building these names no Minecraft class.
     */
    fun earlyProviders(): List<Provider> = listOf(plainProvider(), mixinProvider())

    private fun plainProvider(): Provider = Provider("kts", "kotlin.Any", defaultImports = listOf(Import::class.qualifiedName!!))

    private fun mixinProvider(): Provider = Provider(
        extension = MIXIN_SCRIPT_EXTENSION,
        baseClass = MixinScript::class.qualifiedName!!,
        defaultImports = listOf(
            "ru.hollowhorizon.hollowengine.common.scripting.mixins.*",
            "ru.hollowhorizon.hollowengine.common.scripting.mixins.Point.*",
            Import::class.qualifiedName!!,
        ),
        // Accepts @file:ClientSide: such a script's mixins are left out on a dedicated server.
        clientSideReceivers = emptyList(),
    )

    private fun createProviders(): List<Provider> {
        return buildList {
            this += plainProvider()
            this += Provider(
                extension = RELOAD_SCRIPT_EXTENSION,
                baseClass = ReloadScript::class.qualifiedName!!,
                defaultImports = listOf(
                    "ru.hollowhorizon.hollowengine.common.scripting.annotations.*",
                    ResourceLocation::class.qualifiedName!!,
                    ItemStack::class.qualifiedName!!,
                    "net.minecraft.core.component.DataComponentPatch",
                    SubscribeEvent::class.qualifiedName!!,
                    Import::class.qualifiedName!!,
                    "kotlinx.coroutines.launch",
                    "kotlinx.coroutines.delay",
                    "ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs.item",
                    "ru.hollowhorizon.hollowengine.common.utils.rl",
                    "ru.hollowhorizon.hollowengine.common.utils.literal",
                    "ru.hollowhorizon.hollowengine.common.dialogue.*",
                    "ru.hollowhorizon.hollowengine.common.dialogue.lang.actor",
                    "ru.hollowhorizon.hollowengine.common.dialogue.lang.any",
                    "ru.hollowhorizon.hollowengine.common.dialogue.lang.boolean",
                    "ru.hollowhorizon.hollowengine.common.dialogue.lang.list",
                    "ru.hollowhorizon.hollowengine.common.dialogue.lang.number",
                    "ru.hollowhorizon.hollowengine.common.dialogue.lang.signature",
                    "ru.hollowhorizon.hollowengine.common.dialogue.lang.string",
                ),
                implicitReceivers = listOf(ServerReloadContext::class),
                clientSideReceivers = listOf(CLIENT_RELOAD_CONTEXT),
            )
            this += Provider(
                extension = STARTUP_SCRIPT_EXTENSION,
                baseClass = StartupScript::class.qualifiedName!!,
                defaultImports = listOf(
                    "ru.hollowhorizon.hollowengine.common.scripting.annotations.*",
                    "ru.hollowhorizon.hollowengine.common.events.registry.*",
                    "ru.hollowhorizon.hollowengine.common.registry.getValue",
                    "ru.hollowhorizon.hollowengine.api.AutoModelType",
                    "net.minecraft.world.item.Item",
                    "net.minecraft.world.item.CreativeModeTab",
                    "net.minecraft.world.level.block.Block",
                    "net.minecraft.world.level.block.SoundType",
                    "net.minecraft.world.level.block.state.BlockBehaviour",
                    ResourceLocation::class.qualifiedName!!,
                    ItemStack::class.qualifiedName!!,
                    SubscribeEvent::class.qualifiedName!!,
                    Import::class.qualifiedName!!,
                    "ru.hollowhorizon.hollowengine.common.utils.rl",
                    "ru.hollowhorizon.hollowengine.common.utils.literal",
                ),
                shared = true,
            )
            this += Provider(
                extension = UI_SCRIPT_EXTENSION,
                baseClass = UiScript::class.qualifiedName!!,
                defaultImports = listOf(
                    "androidx.compose.runtime.*",
                    "kotlinx.serialization.*",
                    "ru.hollowhorizon.hollowengine.client.slots.*",
                    "ru.hollowhorizon.hollowengine.client.ui.*",
                    "ru.hollowhorizon.hollowengine.client.ui.widgets.*",
                    "ru.hollowhorizon.hollowengine.client.ui.layout.*",
                    "ru.hollowhorizon.hollowengine.client.ui.text.*",
                    "ru.hollowhorizon.hollowengine.client.ui.style.*",
                    "ru.hollowhorizon.hollowengine.common.data.*",
                    "ru.hollowhorizon.hollowengine.common.ui.*",
                    "ru.hollowhorizon.hollowengine.common.ui.hud.*",
                    "ru.hollowhorizon.hollowengine.common.ui.net.*",
                    "ru.hollowhorizon.hollowengine.client.ui.script.observe",
                    "ru.hollowhorizon.hollowengine.client.ui.script.LocalReplacedScreen",
                    "ru.hollowhorizon.hollowengine.common.scripting.annotations.*",
                    "net.minecraft.nbt.CompoundTag",
                    ResourceLocation::class.qualifiedName!!,
                )
            )
            this += Provider(
                extension = NODE_SCRIPT_EXTENSION,
                baseClass = NodeScript::class.qualifiedName!!,
                defaultImports = listOf(
                    "kotlinx.serialization.*",
                    "ru.hollowhorizon.hollowengine.common.coroutines.*",
                    "ru.hollowhorizon.hollowengine.common.scripting.annotations.*",
                    "ru.hollowhorizon.hollowengine.common.scripting.nodes.*",
                    "ru.hollowhorizon.hollowengine.common.scripting.state.*",
                    "kotlinx.coroutines.delay",
                    "kotlinx.coroutines.yield",
                    "kotlinx.coroutines.withTimeout",
                    "kotlinx.coroutines.withTimeoutOrNull",
                    "kotlin.time.Duration.Companion.days",
                    "kotlin.time.Duration.Companion.hours",
                    "kotlin.time.Duration.Companion.minutes",
                    "kotlin.time.Duration.Companion.seconds",
                    "java.time.Instant",
                    ResourceLocation::class.qualifiedName!!,
                    "net.minecraft.nbt.CompoundTag",
                    "net.minecraft.world.entity.Entity",
                    "net.minecraft.world.entity.LivingEntity",
                    "net.minecraft.world.entity.player.Player",
                    "net.minecraft.world.entity.EquipmentSlot",
                    "net.minecraft.world.InteractionHand",
                    ItemStack::class.qualifiedName!!,
                    "net.minecraft.core.component.DataComponentPatch",
                    "net.minecraft.world.phys.Vec3",
                    "net.minecraft.core.BlockPos",
                    "ru.hollowhorizon.hollowengine.common.scripting.story.functions.*",
                    "ru.hollowhorizon.hollowengine.common.scripting.story.functions.effects.*",
                    "ru.hollowhorizon.hollowengine.common.scripting.story.functions.entities.*",
                    "ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs.*",
                    "ru.hollowhorizon.hollowengine.common.scripting.story.functions.player.*",
                    "ru.hollowhorizon.hollowengine.common.npcs.query.*",
                    "ru.hollowhorizon.hollowengine.common.npcs.items.*",
                    "ru.hollowhorizon.hollowengine.common.npcs.inventory.*",
                    "ru.hollowhorizon.hollowengine.common.slots.*",
                    "ru.hollowhorizon.hollowengine.common.data.*",
                    "ru.hollowhorizon.hollowengine.common.npcs.actions.*",
                    "ru.hollowhorizon.hollowengine.common.npcs.navigation.MoveOptions",
                    "ru.hollowhorizon.hollowengine.common.npcs.navigation.MoveResult",
                    "ru.hollowhorizon.hollowengine.common.npcs.navigation.UnavailableTargetPolicy",
                    "ru.hollowhorizon.hollowengine.common.npcs.navigation.UnreachablePolicy",
                    "ru.hollowhorizon.hollowengine.common.npcs.HitboxMode",
                    "ru.hollowhorizon.hollowengine.common.entities.*",
                    "ru.hollowhorizon.hollowengine.common.attachments.components.*",
                    "ru.hollowhorizon.hollowengine.common.models.*",
                    "ru.hollowhorizon.hollowengine.common.attachments.api.set",
                    "ru.hollowhorizon.hollowengine.common.dialogue.*",
                    "ru.hollowhorizon.hollowengine.common.utils.rl",
                    "ru.hollowhorizon.hollowengine.common.utils.literal",
                    "ru.hollowhorizon.hollowengine.common.utils.sendToast",
                    "ru.hollowhorizon.hollowengine.common.ui.net.*",
                    "ru.hollowhorizon.hollowengine.common.ui.hud.*",
                ),
                implicitReceivers = listOf(
                    MinecraftServer::class
                )
            )
            this += mixinProvider()
            this += Provider(
                extension = CONSOLE_SCRIPT_EXTENSION,
                baseClass = CONSOLE_SCRIPT,
                defaultImports = listOf(
                    Import::class.qualifiedName!!,
                    ResourceLocation::class.qualifiedName!!,
                    ItemStack::class.qualifiedName!!,
                    "net.minecraft.core.BlockPos",
                    "net.minecraft.world.phys.Vec3",
                    "net.minecraft.world.entity.Entity",
                    "net.minecraft.world.entity.LivingEntity",
                    "kotlinx.coroutines.launch",
                    "kotlinx.coroutines.delay",
                    "kotlin.time.Duration.Companion.seconds",
                    "ru.hollowhorizon.hollowengine.common.utils.rl",
                    "ru.hollowhorizon.hollowengine.common.utils.literal",
                ),
            )
        }
    }
}
