package ru.hollowhorizon.hollowengine.client

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.models.internal.v2.NestedModelAttachment
import ru.hollowhorizon.hollowengine.client.particles.BedrockParticles
import ru.hollowhorizon.hollowengine.client.render.RenderManager
import ru.hollowhorizon.hollowengine.client.vfx.VfxAssets
import ru.hollowhorizon.hollowengine.client.vfx.VfxBoneBindings
import ru.hollowhorizon.hollowengine.client.render.entity.EmptyEntityRenderer
import ru.hollowhorizon.hollowengine.client.ui.render.UiPathTileResources
import ru.hollowhorizon.hollowengine.client.ui.screen.HollowUiDemoScreen
import ru.hollowhorizon.hollowengine.client.utils.HollowPack
import ru.hollowhorizon.hollowengine.client.utils.open
import ru.hollowhorizon.hollowengine.common.config.HollowEngineConfig
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterEntityRenderersEvent
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterKeyBindingsEvent
import ru.hollowhorizon.hollowengine.client.ui.text.UiFontResources
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterReloadListenersEvent
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterResourcePacksEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.registry.ModEntities
import ru.hollowhorizon.hollowengine.common.utils.ModList
import ru.hollowhorizon.hollowengine.client.shadergraph.ShaderNodeReloadListener
import ru.hollowhorizon.hollowengine.client.ui.ide.recipe.RecipeEditorsReloadListener
import ru.hollowhorizon.hollowengine.client.decals.DecalAttachment

@ClientOnly
object HollowCoreClient {

    init {
        RenderSystem.recordRenderCall(RenderManager::onInitialize)
        VfxBoneBindings.register()
        DecalAttachment.register()
        NestedModelAttachment.register()
    }

    @SubscribeEvent
    fun onRegisterReloadListener(event: RegisterReloadListenersEvent.Client) {
        event.register(HollowModelManager)
        event.register(BedrockParticles)
        event.register(ShaderNodeReloadListener)
        event.register(RecipeEditorsReloadListener)
        event.register(VfxAssets)
        event.register(UiPathTileResources)
        event.register(UiFontResources)
    }

    @SubscribeEvent
    fun onRegisterResourcePacks(event: RegisterResourcePacksEvent) {
        event.addPack(HollowPack)
    }

    @SubscribeEvent
    fun onRegisterRenderers(event: RegisterEntityRenderersEvent) {
        event.registerEntity(ModEntities.NPC_ENTITY, ::EmptyEntityRenderer)
        event.registerEntity(ModEntities.SEAT, ::EmptyEntityRenderer)
        event.registerEntity(ModEntities.OBJECT, ::EmptyEntityRenderer)
    }

    val KEY_V = KeyMapping("key.v", GLFW.GLFW_KEY_V, "key.v1")

    @SubscribeEvent
    fun onRegisterKeys(event: RegisterKeyBindingsEvent) {
        if (HollowEngineConfig.debugMode) event.registerKeyMapping(KEY_V)
    }

    @SubscribeEvent
    fun onTick(event: TickEvent.Client) {
        if (HollowEngineConfig.debugMode && KEY_V.isDown) {
            HollowUiDemoScreen().open()
        }
    }
}
