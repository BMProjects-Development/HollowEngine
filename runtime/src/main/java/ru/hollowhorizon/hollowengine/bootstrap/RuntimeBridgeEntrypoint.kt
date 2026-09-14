package ru.hollowhorizon.hollowengine.bootstrap

import com.google.common.collect.ImmutableMap
import com.mojang.blaze3d.audio.SoundBuffer
import com.mojang.blaze3d.platform.Window
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.datafixers.util.Either
import net.minecraft.Util
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.model.SkullModelBase
import net.minecraft.client.model.geom.EntityModelSet
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.ParticleEngine
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.resources.PlayerSkin
import net.minecraft.client.player.KeyboardInput
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.entity.EntityRenderer
import net.minecraft.client.renderer.entity.EntityRendererProvider
import net.minecraft.client.sounds.AudioStream
import net.minecraft.client.sounds.LoopingAudioStream
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.NonNullList
import net.minecraft.network.chat.Component
import net.minecraft.resources.FileToIdConverter
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.packs.repository.RepositorySource
import net.minecraft.server.packs.resources.ResourceProvider
import net.minecraft.tags.TagLoader
import net.minecraft.util.Unit
import net.minecraft.world.InteractionHand
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.*
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeManager
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.ConsoleAppender
import ru.hollowhorizon.hollowengine.LOGGER
import ru.hollowhorizon.hollowengine.api.*
import ru.hollowhorizon.hollowengine.api.ModList
import ru.hollowhorizon.hollowengine.api.extensions.FakePlayerFactory
import ru.hollowhorizon.hollowengine.api.extensions.ItemStackHelper
import ru.hollowhorizon.hollowengine.bootstrap.runtime.EventBridge
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimeBridge
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform
import ru.hollowhorizon.hollowengine.client.audio.streams.ExtendedSoundConverter
import ru.hollowhorizon.hollowengine.client.audio.streams.Mp3StreamingAudioStream
import ru.hollowhorizon.hollowengine.client.audio.streams.WavAudioStream
import ru.hollowhorizon.hollowengine.client.editor.TransformGizmoEditor
import ru.hollowhorizon.hollowengine.client.input.ClientKeyWaitManager
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.InstanceBatchManager
import ru.hollowhorizon.hollowengine.client.render.CameraFovEvent
import ru.hollowhorizon.hollowengine.client.render.CameraSetupEvent
import ru.hollowhorizon.hollowengine.client.render.RenderManager
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOverlay
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene.CutsceneCameraSystem
import ru.hollowhorizon.hollowengine.client.ui.script.UiScriptHudHost
import ru.hollowhorizon.hollowengine.common.ui.HudPlacement
import ru.hollowhorizon.hollowengine.common.ui.hud.HudLayerRegistry
import ru.hollowhorizon.hollowengine.common.ui.hud.VanillaHudLayers
import ru.hollowhorizon.hollowengine.client.utils.HollowCoreLoader
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonRuntimeEnvironment
import ru.hollowhorizon.hollowengine.common.compat.util.recipeManagerProtected
import ru.hollowhorizon.hollowengine.common.config.Config
import ru.hollowhorizon.hollowengine.common.coroutines.RuntimeDispatcherState
import ru.hollowhorizon.hollowengine.common.coroutines.ServerRuntimeState
import ru.hollowhorizon.hollowengine.common.events.blocks.BlockEvent
import ru.hollowhorizon.hollowengine.common.events.brew.BrewPotionEvent
import ru.hollowhorizon.hollowengine.common.events.brew.BrewedPlayerPotionEvent
import ru.hollowhorizon.hollowengine.common.events.client.ScreenEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.*
import ru.hollowhorizon.hollowengine.common.events.entity.BabySpawnEvent
import ru.hollowhorizon.hollowengine.common.events.entity.EntityEvent
import ru.hollowhorizon.hollowengine.common.events.entity.ItemEntityEvent
import ru.hollowhorizon.hollowengine.common.events.entity.LivingEntityDeathEvent
import ru.hollowhorizon.hollowengine.common.events.entity.player.PlayerEvent
import ru.hollowhorizon.hollowengine.common.events.entity.player.PlayerInteractEvent
import ru.hollowhorizon.hollowengine.common.events.item.ArrowEvent
import ru.hollowhorizon.hollowengine.common.events.level.LevelEvent
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterParticlesEvent
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterResourcePacksEvent
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterTagsEvent
import ru.hollowhorizon.hollowengine.common.events.server.ServerChatEvent
import ru.hollowhorizon.hollowengine.common.events.server.ServerEvent
import ru.hollowhorizon.hollowengine.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.MaterialsComponent
import ru.hollowhorizon.hollowengine.common.attachments.components.PlayerArms
import ru.hollowhorizon.hollowengine.common.attachments.components.PlayerArmsComponent
import ru.hollowhorizon.hollowengine.client.models.internal.manager.MaterialSources
import ru.hollowhorizon.hollowengine.common.registry.CommonRegistryHelper
import ru.hollowhorizon.hollowengine.common.registry.CommonRegistryProvider
import ru.hollowhorizon.hollowengine.common.scripting.reload.ServerReloadScripts
import ru.hollowhorizon.hollowengine.common.runtime.EmptyRuntimeAnnotationIndex
import ru.hollowhorizon.hollowengine.common.runtime.RuntimeAnnotationEnvironment
import ru.hollowhorizon.hollowengine.common.utils.*
import ru.hollowhorizon.hollowengine.fabric.internal.IrisHelper
import ru.hollowhorizon.hollowengine.network.CommonNetworkManager
import ru.hollowhorizon.hollowengine.runtime.bootstrap.ClassGraphRuntimeAnnotationIndex
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class RuntimeBridgeEntrypoint : RuntimeBridge {
    init {
        if (RuntimeAnnotationEnvironment.annotationIndex === EmptyRuntimeAnnotationIndex) {
            RuntimeAnnotationEnvironment.annotationIndex = ClassGraphRuntimeAnnotationIndex.create()
        }
    }

    override fun setPlatform(platform: RuntimePlatform) {
        HollowAddonRuntimeEnvironment.platform = platform
    }

    override fun setProduction(production: Boolean) {
        isProduction = production
    }

    override fun setClient(physicalClient: Boolean) {
        isPhysicalClient = physicalClient
    }

    override fun events(): EventBridge = EventBridgeImpl

    override fun shouldApplyMixin(targetClassName: String, mixinClassName: String): Boolean {
        return if (mixinClassName.contains(".client.iris.")) {
            isClassPresent("net.irisshaders.iris.Iris")
        } else if (mixinClassName.endsWith(".client.LevelRendererStagesMixin")) {
            !isClassPresent("net.minecraftforge.client.event.RenderLevelStageEvent")
        } else {
            true
        }
    }

    override fun onPlayerInteractEntity(player: Player, hand: InteractionHand, target: Entity): Boolean {
        val event = PlayerInteractEvent.EntityInteract(player, hand, target)
        PlayerInteractEvent.EntityInteract.post(event)
        return event.isCanceled
    }

    override fun onPlayerDrop(
        stack: ItemStack,
        includeThrowerName: Boolean,
        dropped: ItemEntity?,
        player: Player,
    ): ItemEntity? {
        if (dropped == null) return null

        val event = ItemEntityEvent.Toss(dropped, player)
        ItemEntityEvent.Toss.post(event)
        if (event.isCanceled) return null

        if (!player.level().isClientSide) {
            player.commandSenderWorld.addFreshEntity(event.entity)
        }

        return event.entity
    }

    override fun onBrewedPlayerPotion(player: Player, stack: ItemStack) {
        BrewedPlayerPotionEvent.post(BrewedPlayerPotionEvent(player, stack))
    }

    override fun onBrewPotionPre(stacks: NonNullList<ItemStack>): Boolean {
        val snapshot = NonNullList.withSize(stacks.size, ItemStack.EMPTY)
        for (index in 0 until snapshot.size) snapshot[index] = stacks[index].copy()

        val event = BrewPotionEvent.Pre(snapshot)
        BrewPotionEvent.Pre.post(event)
        if (!event.isCanceled) return false

        var changed = false
        for (index in 0 until stacks.size) {
            changed = changed or ItemStack.matches(snapshot[index], stacks[index])
            stacks[index] = event.getItem(index)
        }

        if (changed) onBrewPotionPost(stacks)
        return true
    }

    override fun onBrewPotionPost(stacks: NonNullList<ItemStack>) {
        BrewPotionEvent.Post.post(BrewPotionEvent.Post(stacks))
    }

    override fun onAnimalBreed(self: Animal, mate: Animal, child: AgeableMob?): RuntimeBridge.BreedResult {
        val event = BabySpawnEvent(self, mate, child)
        BabySpawnEvent.post(event)
        return RuntimeBridge.BreedResult(event.child, event.isCanceled)
    }

    override fun onLivingEntityTick(entity: LivingEntity) {
        TickEvent.Entity.post(TickEvent.Entity(entity))
    }

    override fun onLivingEntityDeath(entity: LivingEntity, damageSource: DamageSource): Boolean {
        val event = LivingEntityDeathEvent(entity, damageSource)
        LivingEntityDeathEvent.post(event)
        return event.isCanceled
    }

    override fun augmentPackRepositorySources(providers: Array<RepositorySource>): Array<RepositorySource> {
        return (providers.asList() + RepositorySource { source ->
            RegisterResourcePacksEvent.post(
                RegisterResourcePacksEvent(source)
            )
        }).toTypedArray()
    }

    override fun onPlayerRespawn(original: ServerPlayer, returnFromEnd: Boolean) {
        PlayerEvent.Respawn.post(PlayerEvent.Respawn(original, returnFromEnd))
    }

    override fun onServerChat(player: ServerPlayer, content: Component): RuntimeBridge.ChatResult {
        val event = ServerChatEvent(player, content)
        ServerChatEvent.post(event)
        return RuntimeBridge.ChatResult(event.message, event.isCanceled)
    }

    override fun onServerLevelSave(level: ServerLevel) {
        LevelEvent.Save.post(LevelEvent.Save(level))
    }

    override fun onServerLevelNeighborNotify(level: ServerLevel, pos: BlockPos, sides: MutableSet<Direction>): Boolean {
        val event = BlockEvent.NeighborNotify(level.getBlockState(pos), pos, EnumSet.copyOf(sides))
        BlockEvent.NeighborNotify.post(event)
        return event.isCanceled
    }

    override fun onPlayerClone(self: ServerPlayer, oldPlayer: ServerPlayer, wasDeath: Boolean) {
        PlayerEvent.Clone.post(PlayerEvent.Clone(self, oldPlayer, wasDeath))
    }

    override fun onPlayerSleepInBed(player: ServerPlayer, bedPos: BlockPos): Either<Player.BedSleepingProblem, Unit>? {
        val event = PlayerEvent.SleepInBed(player, null, bedPos)
        PlayerEvent.SleepInBed.post(event)
        return event.problem?.let(Either<Player.BedSleepingProblem, Unit>::left)
    }

    override fun onPlayerWakeup(
        player: ServerPlayer,
        wakeImmediately: Boolean,
        updateLevelForSleepingPlayers: Boolean,
    ) {
        PlayerEvent.Wakeup.post(PlayerEvent.Wakeup(player, wakeImmediately, updateLevelForSleepingPlayers))
    }

    override fun onPlayerUseItemOn(player: ServerPlayer, hand: InteractionHand, hitResult: BlockHitResult): Boolean {
        val event = PlayerInteractEvent.BlockInteract(player, hand, hitResult)
        PlayerInteractEvent.BlockInteract.post(event)
        return event.isCanceled
    }

    override fun onPlayerUseItem(player: ServerPlayer, hand: InteractionHand, stack: ItemStack): Boolean {
        val event = PlayerInteractEvent.ItemInteract(player, hand, stack)
        PlayerInteractEvent.ItemInteract.post(event)
        return event.isCanceled
    }

    override fun onBlockPlaced(player: Player, blockState: BlockState, pos: BlockPos): Boolean {
        val event = BlockEvent.Placed(player, blockState, pos)
        BlockEvent.Placed.post(event)
        return event.isCanceled
    }

    override fun onArrowLoose(stack: ItemStack, level: Level, player: Player, charge: Int, hasAmmo: Boolean): Int {
        val event = ArrowEvent.Loose(stack, level, player, charge, hasAmmo)
        ArrowEvent.Loose.post(event)
        if (event.isCanceled) event.charge = -10
        return BowItem.getPowerForTime(event.charge).let { _ -> event.charge }
    }

    override fun onArrowNock(stack: ItemStack, level: Level, player: Player, usedHand: InteractionHand): ItemStack? {
        val hasAmmo = !player.getProjectile(stack).isEmpty
        val event = ArrowEvent.Nock(stack, level, player, usedHand, hasAmmo)
        ArrowEvent.Nock.post(event)
        return event.stack.takeIf { it != stack }
    }

    override fun onRegisterTags(registry: Any, value: Map<ResourceLocation, List<TagLoader.EntryWithSource>>) {
        @Suppress("UNCHECKED_CAST") RegisterTagsEvent.post(
            RegisterTagsEvent(
                registry as net.minecraft.core.Registry<*>,
                value.mapValuesTo(LinkedHashMap()) { (_, entries) -> entries.toMutableList() },
            )
        )
    }

    override fun getSkySunSize(level: ClientLevel, originalSize: Float): Float {
        val event = SkyRenderEvent.SunSize(level, originalSize)
        SkyRenderEvent.SunSize.post(event)
        return event.sunSize
    }

    override fun getSkyMoonSize(level: ClientLevel, originalSize: Float): Float {
        val event = SkyRenderEvent.MoonSize(level, originalSize)
        SkyRenderEvent.MoonSize.post(event)
        return event.moonSize
    }

    override fun shouldHideGui(currentScreen: Screen?): Boolean {
        return currentScreen is HudHideable && currentScreen.canHideHud()
    }

    override fun onScreenOpen(screen: Screen): Screen {
        val event = ScreenEvent.Open(screen)
        ScreenEvent.Open.post(event)
        return event.screen
    }

    override fun onScreenClose(screen: Screen) {
        ScreenEvent.Close.post(ScreenEvent.Close(screen))
    }

    override fun onScreenRenderPre(
        screen: Screen,
        guiGraphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ): Boolean {
        val event = ScreenEvent.Render.Pre(screen, guiGraphics, mouseX, mouseY, partialTick)
        ScreenEvent.Render.Pre.post(event)
        return event.isCanceled
    }

    override fun onScreenRenderPost(
        screen: Screen,
        guiGraphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        ScreenEvent.Render.Post.post(ScreenEvent.Render.Post(screen, guiGraphics, mouseX, mouseY, partialTick))
    }

    override fun onRenderArm(
        stack: PoseStack,
        multiBufferSource: MultiBufferSource,
        packedLight: Int,
        player: AbstractClientPlayer,
        arm: HumanoidArm,
    ): Boolean = RenderArmEvent.post(RenderArmEvent(stack, multiBufferSource, packedLight, player, arm)).isCanceled

    override fun onRenderItemInHand(camera: Camera, partialTick: Float, projectionMatrix: Matrix4f): Boolean =
        RenderItemInHandEvent.post(RenderItemInHandEvent(camera, partialTick, projectionMatrix)).isCanceled

    override fun onRegisterParticles(particleEngine: ParticleEngine) {
        RegisterParticlesEvent.post(RegisterParticlesEvent(particleEngine))
    }

    override fun onClientUseItemOn(player: Player, hand: InteractionHand, hitResult: BlockHitResult): Boolean {
        val event = PlayerInteractEvent.BlockInteract(player, hand, hitResult)
        PlayerInteractEvent.BlockInteract.post(event)
        return event.isCanceled
    }

    override fun onClientInteractEntity(player: Player, hand: InteractionHand, target: Entity): Boolean {
        val event = PlayerInteractEvent.EntityInteract(player, hand, target)
        PlayerInteractEvent.EntityInteract.post(event)
        return event.isCanceled
    }

    override fun onClientUseItem(player: Player, hand: InteractionHand, stack: ItemStack): Boolean {
        val event = PlayerInteractEvent.ItemInteract(player, hand, stack)
        PlayerInteractEvent.ItemInteract.post(event)
        return event.isCanceled
    }

    override fun onDebugClientMain() {
        ConsoleAppender.attach()
        HollowCoreLoader.initialize()
        Config.startObserver()

        LOGGER.info(
            "Production: {}, Can attach renderdoc: {}, Platform: {}",
            isProduction,
            HollowCoreLoader.canAttachRenderdoc(),
            Util.getPlatform().name
        )
        if (!HollowCoreLoader.canAttachRenderdoc() || Util.getPlatform() != Util.OS.WINDOWS) {
            return
        }

        val path = System.getProperty("java.library.path")
        val name = System.mapLibraryName("renderdoc")
        var detected = false
        for (folder in path.split(";")) {
            if (java.nio.file.Files.exists(java.nio.file.Path.of("$folder/$name"))) {
                detected = true
                break
            }
        }

        LOGGER.info("Is detected renderdoc: {}", detected)

        if (!detected) {
            val renderDoc = java.nio.file.Path.of("C:/Program Files/RenderDoc/renderdoc.dll")
            if (java.nio.file.Files.exists(renderDoc)) {
                try {
                    LOGGER.info("Trying load system renderdoc")
                    System.load("C:/Program Files/RenderDoc/renderdoc.dll")
                    LOGGER.info("Renderdoc Loaded")
                } catch (e: Throwable) {
                    LOGGER.info("Failed to load Renderdoc", e)
                }
            }
            return
        }

        try {
            System.loadLibrary("renderdoc")
            LOGGER.info("Renderdoc Loaded")
        } catch (e: Throwable) {
            LOGGER.info("Failed to load Renderdoc", e)
        }
    }

    override fun onLoadCompleteSound(
        soundId: ResourceLocation,
        resourceManager: ResourceProvider,
        cache: Map<ResourceLocation, CompletableFuture<SoundBuffer>>,
    ): CompletableFuture<SoundBuffer>? {
        val path = soundId.path
        if (!path.endsWith(".mp3") && !path.endsWith(".wav")) return null

        @Suppress("UNCHECKED_CAST") val mutableCache =
            cache as MutableMap<ResourceLocation, CompletableFuture<SoundBuffer>>
        return mutableCache.computeIfAbsent(soundId) { resourceLocation ->
            CompletableFuture.supplyAsync({
                try {
                    resourceManager.open(resourceLocation).use { inputStream ->
                        loadSoundBuffer(path, inputStream)
                    }
                } catch (ioException: IOException) {
                    throw CompletionException(ioException)
                }
            }, Util.backgroundExecutor())
        }
    }

    override fun onLoadStreamSound(
        soundId: ResourceLocation,
        resourceManager: ResourceProvider,
        isWrapper: Boolean,
    ): CompletableFuture<AudioStream>? {
        val path = soundId.path
        if (!path.endsWith(".mp3") && !path.endsWith(".wav")) return null

        return CompletableFuture.supplyAsync({
            try {
                val inputStream = resourceManager.open(soundId)
                if (path.endsWith(".wav")) {
                    if (isWrapper) LoopingAudioStream(::WavAudioStream, inputStream) else WavAudioStream(inputStream)
                } else {
                    if (isWrapper) LoopingAudioStream(
                        ::Mp3StreamingAudioStream, inputStream
                    ) else Mp3StreamingAudioStream(inputStream)
                }
            } catch (ioException: IOException) {
                throw CompletionException(ioException)
            }
        }, Util.backgroundExecutor())
    }

    override fun createSoundConverter(): FileToIdConverter = ExtendedSoundConverter

    override fun getOpenGlVersionOverride(): String = HollowCoreLoader.openGlVersion

    override fun shouldForceAutoGuiScale(screen: Screen?): Boolean = screen is AutoScaled

    override fun onBlitScreen(minecraft: Minecraft) {
        RenderTickEvent.Blit.post(RenderTickEvent.Blit(minecraft))
    }

    override fun onServerCreated(server: MinecraftServer, serverThread: Thread, levelRoot: Path) {
        currentServer = server
        RuntimeDispatcherState.createServer(server, serverThread)
        ServerRuntimeState.create(server, levelRoot)
    }

    override fun onServerStarting(server: MinecraftServer) {
        currentServer = server
        ServerEvent.Starting.post(ServerEvent.Starting(server))
    }

    override fun onServerLevelsCreated(server: MinecraftServer) {
        ServerRuntimeState.load(server)
        server.allLevels.forEach { level -> LevelEvent.Load.post(LevelEvent.Load(level)) }
    }

    override fun onServerTick(server: MinecraftServer) {
        RuntimeDispatcherState.runServerTasks(server)
    }

    override fun onServerStopping(server: MinecraftServer) {
        ServerRuntimeState.save(server)
        ServerEvent.Stoping.post(ServerEvent.Stoping(server))
    }

    override fun onServerStopped(server: MinecraftServer) {
        ServerReloadScripts.stop()
        RuntimeDispatcherState.stopServer(server)
        ServerRuntimeState.remove(server)
        clearCurrentServer(server)
    }

    override fun onClientCreated(client: Minecraft) {
        RuntimeDispatcherState.createClient(client)
    }

    override fun onClientTick(client: Minecraft) {
        RuntimeDispatcherState.runClientTasks(client)
    }

    override fun onClientRenderTickPre(client: Minecraft) {
        CutsceneCameraSystem.update(client)
        RenderTickEvent.Pre.post(RenderTickEvent.Pre(client))
    }

    override fun onClientRenderTickPost(client: Minecraft) {
        RenderTickEvent.Post.post(RenderTickEvent.Post(client))
    }

    override fun onClientResized(client: Minecraft) {
    }

    override fun onClientStopping(client: Minecraft) {
        RuntimeDispatcherState.stopClient(client)
    }

    override fun onLevelCreated(level: Level) {
        AttachmentRegistry.initLevel(level)
    }

    override fun onLevelUpdateNeighbors(level: Level, pos: BlockPos) {
        BlockEvent.NeighborNotify.post(
            BlockEvent.NeighborNotify(
                level.getBlockState(pos),
                pos,
                EnumSet.allOf(Direction::class.java)
            )
        )
    }

    override fun onLevelTickBlockEntities(level: Level) {
        val profiler = level.profiler
        profiler.push("HollowEngine ECS")
        AttachmentRegistry.tick(level)
        profiler.pop()
    }

    override fun onLevelClosed(level: Level) {
        AttachmentRegistry.close(level)
    }

    override fun onEntitySaved(entity: Entity, tag: net.minecraft.nbt.CompoundTag) {
        AttachmentRegistry.saveEntity(entity, tag)
    }

    override fun onEntityLoaded(entity: Entity, tag: net.minecraft.nbt.CompoundTag) {
        AttachmentRegistry.loadEntity(entity, tag)
    }

    override fun onEntityHurt(entity: Entity, damageSource: DamageSource, amount: Float): Boolean {
        val event = EntityEvent.Hurt(entity, damageSource, amount)
        EntityEvent.Hurt.post(event)
        return event.isCanceled
    }

    override fun onEntityChangedDimension(entity: Entity, resultEntity: Entity?, fromLevel: Level, toLevel: Level) {
        if (resultEntity != null) {
            EntityEvent.ChangeDimension.post(EntityEvent.ChangeDimension(entity, resultEntity, fromLevel, toLevel))
        }
    }

    override fun onEntitySetLevel(entity: Entity, level: Level) {
        AttachmentRegistry.onSetLevel(entity, level)
    }

    override fun onEntityRemoved(entity: Entity) {
        AttachmentRegistry.onRemove(entity)
    }

    override fun onRecipeManagerCreated(recipeManager: RecipeManager) {
        recipeManagerProtected = recipeManager
    }

    override fun onAddEntityRendererLayers(
        renderers: MutableMap<EntityType<*>, EntityRenderer<*>>,
        playerRenderers: MutableMap<String, EntityRenderer<out Player>>,
        context: EntityRendererProvider.Context,
    ) {
        AddEntityRendererLayers.post(AddEntityRendererLayers(renderers, playerRenderers, context))
    }

    override fun onRenderEntityPre(
        entity: Entity,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
    ): Boolean {
        val event = RenderEntityEvent.Pre(entity, entityYaw, partialTick, poseStack, buffer, packedLight)
        RenderEntityEvent.Pre.post(event)
        return event.isCanceled
    }

    override fun onRenderEntityPost(
        entity: Entity,
        entityYaw: Float,
        partialTick: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
    ) {
        RenderEntityEvent.Post.post(
            RenderEntityEvent.Post(
                entity,
                entityYaw,
                partialTick,
                poseStack,
                buffer,
                packedLight
            )
        )
    }

    override fun extendEntityCullingBounds(entity: Entity, vanillaBounds: AABB): AABB =
        RenderManager.extendCullingBounds(entity, vanillaBounds)

    override fun isEntityFrustumCullingDisabled(entity: Entity): Boolean =
        RenderManager.isFrustumCullingDisabled(entity)

    override fun onRenderEntityNameplate(entity: Entity, vanillaVisible: Boolean): Boolean {
        val event = RenderEntityNameplateEvent(entity, vanillaVisible)
        RenderEntityNameplateEvent.post(event)
        return event.isVisible
    }

    override fun onCameraSetup(
        gameRenderer: GameRenderer,
        camera: Camera,
        yaw: Float,
        pitch: Float,
        roll: Float,
        partialTick: Float,
    ): RuntimeBridge.CameraSetup {
        val pose = CutsceneCameraSystem.currentPose.takeIf { shouldApplyCutsceneCamera() }
        val event = CameraSetupEvent(
            gameRenderer = gameRenderer,
            camera = camera,
            partialTick = partialTick,
            yaw = pose?.yaw ?: yaw,
            pitch = pose?.pitch ?: pitch,
            roll = pose?.roll ?: roll,
        )
        CameraSetupEvent.post(event)
        return RuntimeBridge.CameraSetup(event.yaw, event.pitch, event.roll)
    }

    override fun getCameraOverride(partialTick: Float): RuntimeBridge.CameraOverride {
        if (!shouldApplyCutsceneCamera()) return RuntimeBridge.CameraOverride.NONE
        val pose = CutsceneCameraSystem.currentPose ?: return RuntimeBridge.CameraOverride.NONE
        return RuntimeBridge.CameraOverride(
            true,
            pose.position.x.toDouble(),
            pose.position.y.toDouble(),
            pose.position.z.toDouble(),
            pose.yaw,
            pose.pitch,
            pose.roll,
            pose.fov.toDouble(),
        )
    }

    override fun onCameraFov(
        gameRenderer: GameRenderer,
        camera: Camera,
        fov: Double,
        partialTick: Float,
        changingFov: Boolean,
    ): Double {
        val event = CameraFovEvent(
            gameRenderer = gameRenderer,
            camera = camera,
            partialTick = partialTick,
            changingFov = changingFov,
            fov = CutsceneCameraSystem.currentPose?.takeIf { shouldApplyCutsceneCamera() }?.fov?.toDouble() ?: fov,
        )
        CameraFovEvent.post(event)
        return event.fov
    }

    private fun shouldApplyCutsceneCamera(): Boolean {
        return !IrisHelper.isShadowRendering()
    }

    override fun onRegisterLayerDefinitions(definitions: MutableMap<ModelLayerLocation, java.util.function.Supplier<LayerDefinition>>) {
        val eventDefinitions = HashMap<ModelLayerLocation, () -> LayerDefinition>()
        val event = RegisterEntityLayersDefinitions(eventDefinitions)
        RegisterEntityLayersDefinitions.post(event)
        eventDefinitions.forEach { (location, definition) ->
            definitions[location] = java.util.function.Supplier { definition() }
        }
    }

    override fun onCreateSkullModels(
        builder: ImmutableMap.Builder<SkullBlock.Type, SkullModelBase>,
        entityModelSet: EntityModelSet,
    ) {
        CreateEntitySkullModels.post(CreateEntitySkullModels(builder, entityModelSet))
    }

    override fun onRenderPlayer(
        player: AbstractClientPlayer,
        entityYaw: Float,
        partialTicks: Float,
        poseStack: PoseStack,
        buffer: MultiBufferSource,
        packedLight: Int,
    ): Boolean {
        val event = RenderPlayerEvent(player, entityYaw, partialTicks, poseStack, buffer, packedLight)
        RenderPlayerEvent.post(event)
        return event.isCanceled
    }

    override fun customizePlayerSkin(player: AbstractClientPlayer, vanilla: PlayerSkin): PlayerSkin? {
        val components = AttachmentRegistry.componentsById(player)
        val materialsId = ComponentDescriptorRegistry.idFor(MaterialsComponent::class)
        val materials = materialsId?.let { components[it] as? MaterialsComponent }
        val armsId = ComponentDescriptorRegistry.idFor(PlayerArmsComponent::class)
        val arms = armsId?.let { (components[it] as? PlayerArmsComponent)?.arms }

        val skinSource = materials?.materials?.get(MaterialsComponent.SKIN)
        val skin = skinSource?.let(MaterialSources::resolve)
        val cape = materials?.materials?.get(MaterialsComponent.CAPE)?.let(MaterialSources::resolve)
        val elytra = materials?.materials?.get(MaterialsComponent.ELYTRA)?.let(MaterialSources::resolve)
        if (skin == null && cape == null && elytra == null && arms == null) return null

        return PlayerSkin(
            skin?.texture ?: vanilla.texture(),
            vanilla.textureUrl(),
            cape?.texture ?: vanilla.capeTexture(),
            elytra?.texture ?: vanilla.elytraTexture(),
            when (arms ?: skinSource?.let(MaterialSources::armsOf)) {
                PlayerArms.SLIM -> PlayerSkin.Model.SLIM
                PlayerArms.WIDE -> PlayerSkin.Model.WIDE
                null -> vanilla.model()
            },
            vanilla.secure(),
        )
    }

    override fun onIrisPipelineDestroyed() {
        IrisHelper.invalidateInstancingPrograms()
    }

    override fun onIrisShadowRenderStart() {
        InstanceBatchManager.clear()
    }

    override fun onIrisShadowRenderBeforeEndBatch() {
        InstanceBatchManager.flush()
    }

    override fun onIrisShadowRenderEnd() {
        InstanceBatchManager.clear()
    }

    override fun onRenderOverlayPre(
        window: Window,
        guiGraphics: GuiGraphics,
        partialTick: Float,
        layerId: String,
    ): Boolean {
        val layer = ResourceLocation.parse(layerId)
        val event = RenderOverlayEvent.Pre(window, guiGraphics, partialTick, layer)
        RenderOverlayEvent.Pre.post(event)

        val nowNanos = System.nanoTime()
        UiScriptHudHost.render(layer, HudPlacement.BEFORE, nowNanos)

        val skip = event.isCanceled || HudLayerRegistry.isHidden(layer)
        if (skip) UiScriptHudHost.render(layer, HudPlacement.AFTER, nowNanos)
        return skip
    }

    override fun onRenderOverlayPost(
        window: Window,
        guiGraphics: GuiGraphics,
        partialTick: Float,
        layerId: String,
    ) {
        val layer = ResourceLocation.parse(layerId)
        RenderOverlayEvent.Post.post(RenderOverlayEvent.Post(window, guiGraphics, partialTick, layer))
        UiScriptHudHost.render(layer, HudPlacement.AFTER, System.nanoTime())
    }

    override fun onRenderHudPost(window: Window, guiGraphics: GuiGraphics, partialTick: Float) {
        RenderHudEvent.post(RenderHudEvent(window, guiGraphics, partialTick))
    }

    override fun onKeyboardKey(windowPointer: Long, key: Int, scanCode: Int, action: Int, modifiers: Int): Boolean {
        ClientKeyWaitManager.handleKey(key, action)
        if (HollowIdeOverlay.handleKey(key, scanCode, action, modifiers)) return true
        if (TransformGizmoEditor.handleKey(key, scanCode, action, modifiers)) return true
        if (UiScriptHudHost.handleKey(key, scanCode, action, modifiers)) return true
        return false
    }

    override fun onKeyboardChar(windowPointer: Long, codePoint: Int, modifiers: Int): Boolean {
        if (HollowIdeOverlay.handleChar(codePoint, modifiers)) return true
        if (TransformGizmoEditor.handleChar(codePoint, modifiers)) return true
        if (UiScriptHudHost.handleChar(codePoint, modifiers)) return true
        return false
    }

    override fun onMouseMove(
        minecraft: Minecraft,
        windowPointer: Long,
        xPos: Double,
        yPos: Double,
    ): RuntimeBridge.MouseMoveResult {
        val window = minecraft.window
        val scaleFactor = minecraft.mainRenderTarget.width.toDouble() / window.screenWidth
        val convertedX = (xPos * scaleFactor).toFloat()
        val convertedY = (yPos * scaleFactor).toFloat()

        val isOverlayInputCaptured = HollowIdeOverlay.handleMouseMove(convertedX, convertedY)
        val isGizmoInputCaptured = TransformGizmoEditor.handleMouseMove(convertedX, convertedY)
        val (guiX, guiY) = guiScaledPointer(minecraft, xPos, yPos)
        val isScriptOverlayCaptured = UiScriptHudHost.handleMouseMove(guiX, guiY)
        val isScreenOpen = minecraft.screen != null
        val isGizmoBlocking = TransformGizmoEditor.shouldBlockScreenInput(convertedX, convertedY)
        val shouldCancel = isOverlayInputCaptured || isGizmoInputCaptured || isScriptOverlayCaptured || isGizmoBlocking && isScreenOpen
        val shouldResetMousePosition = isGizmoBlocking && isScreenOpen
        return RuntimeBridge.MouseMoveResult(convertedX, convertedY, shouldCancel, shouldResetMousePosition)
    }

    /** Converts a raw window cursor position to the GUI-scaled coordinate space overlays render in. */
    private fun guiScaledPointer(minecraft: Minecraft, xPos: Double, yPos: Double): Pair<Float, Float> {
        val window = minecraft.window
        val guiX = xPos * window.guiScaledWidth / window.screenWidth
        val guiY = yPos * window.guiScaledHeight / window.screenHeight
        return guiX.toFloat() to guiY.toFloat()
    }

    override fun onMousePress(
        minecraft: Minecraft,
        x: Float,
        y: Float,
        windowPointer: Long,
        button: Int,
        action: Int,
        modifiers: Int,
    ): Boolean {
        val (guiX, guiY) = guiScaledPointer(minecraft, minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos())
        return HollowIdeOverlay.handleMouseButton(x, y, button, action) ||
                TransformGizmoEditor.handleMouseButton(x, y, button, action) ||
                UiScriptHudHost.handleMouseButton(guiX, guiY, button, action) ||
                TransformGizmoEditor.shouldBlockScreenInput(x, y)
    }

    override fun onMouseScroll(
        minecraft: Minecraft,
        x: Float,
        y: Float,
        windowPointer: Long,
        xOffset: Double,
        yOffset: Double,
    ): Boolean {
        val (guiX, guiY) = guiScaledPointer(minecraft, minecraft.mouseHandler.xpos(), minecraft.mouseHandler.ypos())
        return HollowIdeOverlay.handleMouseScroll(x, y, xOffset, yOffset) ||
                TransformGizmoEditor.handleMouseScroll(x, y, xOffset, yOffset) ||
                UiScriptHudHost.handleMouseScroll(guiX, guiY, xOffset, yOffset) ||
                TransformGizmoEditor.shouldBlockScreenInput(x, y)
    }

    override fun onRenderLevelStage(
        renderer: LevelRenderer,
        poseStack: PoseStack,
        projectionMatrix: Matrix4f,
        ticks: Int,
        partialTick: Float,
        camera: Camera,
        frustum: Frustum?,
        stage: RuntimeBridge.RenderLevelStage,
    ) {
        RenderLevelStageEvent.post(
            RenderLevelStageEvent(
                renderer, poseStack, projectionMatrix, ticks, partialTick, camera, frustum, stage.toRenderStage()
            )
        )
    }

    override fun onCommonInitialize() {
        ru.hollowhorizon.hollowengine.fabric.HCInit.onCommonInitialize()
    }

    override fun onClientInitialize() {
        ru.hollowhorizon.hollowengine.fabric.HCInit.onClientInitialize()
    }

    override fun initFakePlayers(factory: FakePlayerFactory) {
        FakePlayer.init(factory)
    }

    override fun initStackHelper(helder: ItemStackHelper) {
        ItemStackUtil.init(helder)
    }

    override fun initNetwork(networkManager: NetworkManager) {
        CommonNetworkManager.init(networkManager)
    }

    override fun initModList(modList: ModList) {
        ru.hollowhorizon.hollowengine.common.utils.ModList.init(modList)
    }

    @Suppress("UNCHECKED_CAST")
    override fun initRegistryProvider(provider: RegistryProvider<*>) {
        CommonRegistryProvider.init(provider as RegistryProvider<Any>)
    }

    override fun getRegistryHelper(): RegistryHelper = CommonRegistryHelper

    override fun close() {
        val index = RuntimeAnnotationEnvironment.annotationIndex
        RuntimeAnnotationEnvironment.annotationIndex = EmptyRuntimeAnnotationIndex
        if (index is AutoCloseable) {
            index.close()
        }
    }

    private fun isClassPresent(name: String): Boolean {
        val classPath = name.replace('.', '/') + ".class"
        return javaClass.classLoader.getResource(classPath) != null
    }

    private fun loadSoundBuffer(path: String, inputStream: InputStream): SoundBuffer {
        return if (path.endsWith(".wav")) {
            WavAudioStream(inputStream).use { stream ->
                val byteBuffer: ByteBuffer = stream.readAll()
                SoundBuffer(byteBuffer, stream.format)
            }
        } else {
            Mp3StreamingAudioStream(inputStream).use { stream ->
                val byteBuffer: ByteBuffer = stream.readAll()
                SoundBuffer(byteBuffer, stream.format)
            }
        }
    }

    private fun RuntimeBridge.RenderLevelStage.toRenderStage(): RenderStage = when (this) {
        RuntimeBridge.RenderLevelStage.AFTER_LEVEL -> RenderStage.AFTER_LEVEL
        RuntimeBridge.RenderLevelStage.AFTER_SKY -> RenderStage.AFTER_SKY
        RuntimeBridge.RenderLevelStage.AFTER_ENTITIES -> RenderStage.AFTER_ENTITIES
        RuntimeBridge.RenderLevelStage.AFTER_BLOCK_ENTITIES -> RenderStage.AFTER_BLOCK_ENTITIES
        RuntimeBridge.RenderLevelStage.AFTER_PARTICLES -> RenderStage.AFTER_PARTICLES
        RuntimeBridge.RenderLevelStage.AFTER_WEATHER -> RenderStage.AFTER_WEATHER
        RuntimeBridge.RenderLevelStage.AFTER_SOLID_BLOCKS -> RenderStage.AFTER_SOLID_BLOCKS
        RuntimeBridge.RenderLevelStage.AFTER_CUTOUT_MIPPED_BLOCKS -> RenderStage.AFTER_CUTOUT_MIPPED_BLOCKS
        RuntimeBridge.RenderLevelStage.AFTER_CUTOUT_BLOCKS -> RenderStage.AFTER_CUTOUT_BLOCKS
        RuntimeBridge.RenderLevelStage.AFTER_TRANSLUCENT_BLOCKS -> RenderStage.AFTER_TRANSLUCENT_BLOCKS
        RuntimeBridge.RenderLevelStage.AFTER_TRIPWIRE_BLOCKS -> RenderStage.AFTER_TRIPWIRE_BLOCKS
    }
}
