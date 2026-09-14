package ru.hollowhorizon.hollowengine.bootstrap

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.commands.CommandBuildContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeSupplier
import net.minecraft.world.item.CreativeModeTab
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import ru.hollowhorizon.hollowengine.bootstrap.runtime.EventBridge
import ru.hollowhorizon.hollowengine.common.events.blocks.BlockEvent
import ru.hollowhorizon.hollowengine.common.events.client.ItemTooltipEvent
import ru.hollowhorizon.hollowengine.common.events.entity.EntityLoadedEvent
import ru.hollowhorizon.hollowengine.common.events.entity.EntityTrackingEvent
import ru.hollowhorizon.hollowengine.common.events.entity.player.PlayerEvent
import ru.hollowhorizon.hollowengine.common.events.item.BuildTabContentsEvent
import ru.hollowhorizon.hollowengine.common.events.registry.*
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.scripting.reload.ServerReloadScripts
import java.util.function.BiConsumer
import java.util.function.Consumer

object EventBridgeImpl : EventBridge {
    override fun onRegisterShaders(shaders: EventBridge.ShaderRegistration) {
        val event = RegisterShadersEvent.post(RegisterShadersEvent())
        event.shaders.forEach {
            shaders.register(it.key, it.value.first, it.value.second)
        }
    }

    override fun onRegisterEntityRenderers(consumer: BiConsumer<EntityType<out Entity>, EntityRendererProvider<Entity>>) {
        RegisterEntityRenderersEvent.post(RegisterEntityRenderersEvent { a, b -> consumer.accept(a, b) })
    }

    override fun onRegisterBlockEntityRenderers(consumer: BiConsumer<BlockEntityType<out BlockEntity>, BlockEntityRendererProvider<BlockEntity>>) {
        RegisterBlockEntityRenderersEvent.post(RegisterBlockEntityRenderersEvent { a, b -> consumer.accept(a, b) })
    }

    override fun onRegisterKeybindings(consumer: Consumer<KeyMapping>) {
        RegisterKeyBindingsEvent.post(RegisterKeyBindingsEvent(consumer::accept))
    }

    override fun onRegisterClientReloadListeners(registration: EventBridge.ReloadListenerRegistration) {
        val event = RegisterReloadListenersEvent.Client.post(RegisterReloadListenersEvent.Client())
        event.listeners.forEach(registration::register)
    }

    override fun onRegisterServerReloadListeners(registration: EventBridge.ReloadListenerRegistration) {
        val event = RegisterReloadListenersEvent.Server.post(RegisterReloadListenersEvent.Server())
        event.listeners.forEach(registration::register)
    }

    override fun onRegisterEntityAttributes(
        consumer: BiConsumer<EntityType<out LivingEntity>, AttributeSupplier>,
    ) {
        val event = RegisterEntityAttributesEvent.post(RegisterEntityAttributesEvent())
        event.getAttributes().forEach(consumer::accept)
    }

    override fun onGetTooltip(
        stack: ItemStack,
        tooltip: Item.TooltipContext,
        flags: TooltipFlag,
        lines: MutableList<Component>,
    ) {
        ItemTooltipEvent.post(ItemTooltipEvent(flags, stack, lines, tooltip))
    }

    override fun onClientTick(minecraft: Minecraft) {
        TickEvent.Client.post(TickEvent.Client(minecraft))
    }

    override fun onServerTick(server: MinecraftServer) {
        TickEvent.Server.post(TickEvent.Server(server))
    }

    override fun onRegisterCommands(
        dispatcher: CommandDispatcher<CommandSourceStack>,
        ctx: CommandBuildContext,
        environment: Commands.CommandSelection,
    ) {
        ServerReloadScripts.onCommandsCreated(RegisterCommandsEvent(dispatcher, ctx, environment))
    }

    override fun onClientCommandRegistration(
        dispatcher: CommandDispatcher<SharedSuggestionProvider>,
        ctx: CommandBuildContext,
    ) {
        RegisterClientCommandsEvent.post(RegisterClientCommandsEvent(dispatcher, ctx))
    }

    override fun onEntityTrackingStart(player: ServerPlayer, entity: Entity) {
        EntityTrackingEvent.Start.post(EntityTrackingEvent.Start(player, entity))
    }

    override fun onEntityTrackingStop(player: ServerPlayer, entity: Entity) {
        EntityTrackingEvent.Stop.post(EntityTrackingEvent.Stop(player, entity))
    }

    override fun onPlayerJoin(player: ServerPlayer) {
        PlayerEvent.Join.post(PlayerEvent.Join(player))
    }

    override fun onPlayerLeave(player: ServerPlayer) {
        PlayerEvent.Leave.post(PlayerEvent.Leave(player))
    }

    override fun onPlayerChangeDimension(player: ServerPlayer, from: ServerLevel, to: ServerLevel) {
        PlayerEvent.ChangeDimension.post(PlayerEvent.ChangeDimension(player, from, to))
    }

    override fun onEntityLoad(entity: Entity) {
        EntityLoadedEvent.post(EntityLoadedEvent(entity))
    }

    override fun onBlockBreak(level: Level, pos: BlockPos, state: BlockState, player: ServerPlayer): Boolean {
        val event = BlockEvent.Break(level, pos, state, player)
        return BlockEvent.Break.post(event).isCanceled
    }

    override fun onBuildTabContents(
        tab: CreativeModeTab,
        tabKey: ResourceKey<CreativeModeTab>,
        parameters: CreativeModeTab.ItemDisplayParameters,
        output: CreativeModeTab.Output,
    ) {
        BuildTabContentsEvent.post(BuildTabContentsEvent(tab, tabKey, parameters, output::accept))
    }
}
