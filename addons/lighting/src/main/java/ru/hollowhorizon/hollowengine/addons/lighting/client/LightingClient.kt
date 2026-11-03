package ru.hollowhorizon.hollowengine.addons.lighting.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL30
import org.qualet.irl.light.*
import org.qualet.irl.light.shadow.HollowShadowCasters
import org.qualet.irl.light.shadow.ShadowBaker
import org.qualet.irl.light.shadow.ShadowEngine
import org.qualet.irl.patcher.Patcher
import ru.hollowhorizon.hollowengine.addons.lighting.LightFrame
import ru.hollowhorizon.hollowengine.addons.lighting.LightInstance
import ru.hollowhorizon.hollowengine.addons.lighting.LightSource
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.api.extensions.closeWith
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RigAttachmentFactories
import ru.hollowhorizon.hollowengine.client.render.RenderPasses
import ru.hollowhorizon.hollowengine.common.coroutines.RuntimeDispatcherState
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.level.LevelEvent
import ru.hollowhorizon.hollowengine.common.lighting.LightAttachmentSpec
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.fabric.internal.IrisHelper

/** HollowEngine source/lifecycle adapter. GPU formats and shadow orchestration belong to irl-core. */
@ClientOnly
object LightingClient {
    val sources = ExtensionPoints.create<LightSource>("hollowengine-lighting:sources".rl)

    private data class Attached(val light: LightInstance, val entity: Entity, val frame: Long)

    @Volatile
    private var active = false

    @Volatile
    private var stopped = false
    private val attachments = LinkedHashMap<String, Attached>()
    private val identities = HashMap<String, Long>()
    private var nextIdentity = 0L
    private val frame = ArrayList<LightInstance>()

    fun load(scope: CoroutineScope) {
        LightingSettings.initialize()
        LightingCommands.load(scope)
        Patcher.install(LightingPatcherHost)
        ShadowEngine.install(HollowShadowCasters(), LightingSettings.shadows)
        IrlSamplers.register("irl_cookieArray", LightCookies::texture, GL30.GL_TEXTURE_2D_ARRAY)
        sources.register("hollowengine-lighting:components".rl, LightSource(LightComponents::collect)).closeWith(scope)
        RigAttachmentFactories.register(LightAttachmentSpec.TYPE.id) { spec, context ->
            LightAttachment(spec as LightAttachmentSpec, context.node)
        }.closeWith(scope)
        stopped = false
        active = true
    }

    suspend fun unload() {
        if (stopped) return
        withContext(RuntimeDispatcherState.clientDispatcher(Minecraft.getInstance())) {
            active = false
            try {
                LightingShaderpacks.restoreOriginal()
            } finally {
                release()
            }
        }
    }

    /** Minecraft closes its GL context after this hook. Keep the selected shaderpack across normal exits. */
    fun onClientClose() {
        active = false
        stopped = true
        release()
    }

    /** Before Iris activates, matching IRLights: collect sources and bake light-relative shadows. */
    fun beginFrame(partialTick: Float) {
        if (!active || !GL.getCapabilities().OpenGL43) return
        LightingSettings.applyGlobals()
        RenderPasses.shadow {
            FramePipeline.frame(partialTick, { !IrisHelper.areShadersEnabled() }, ::collect, {})
        }
    }

    private fun collect(world: ClientLevel?, camera: Vec3, partialTick: Float) {
        frame.clear()
        sources.forEach { it.collect(partialTick, frame::add) }
        attachments.entries.removeIf { (_, it) ->
            it.entity.level() !== world || it.entity.isRemoved || it.frame < TickHandler.renderFrame - 1
        }
        frame.addAll(attachments.values.map(Attached::light))
        val selected = LightFrame.select(frame, camera.x, camera.y, camera.z)
        identities.keys.retainAll(selected.mapTo(HashSet(), LightInstance::id))
        selected.forEach { light ->
            IrlLightEmitter.emit(light, identities.getOrPut(light.id) { ++nextIdentity })
        }
    }

    /** After Camera.setup: the SSBO uses the same eye position as this frame's shaderpack. */
    fun uploadFrame() {
        if (active && GL.getCapabilities().OpenGL43) FramePipeline.uploadIfPending()
    }

    fun reloadResources() {
        if (!active) return
        LightCookies.close()
        if (GL.getCapabilities().OpenGL43) ShadowBaker.onShadersDisabled()
    }

    internal fun submitAttachment(light: LightInstance, entity: Entity) {
        if (active) attachments[light.id] = Attached(light, entity, TickHandler.renderFrame)
    }

    @SubscribeEvent
    fun onLevelUnload(event: LevelEvent.Unload) {
        if (!event.level.isClientSide) return
        Minecraft.getInstance().execute {
            clear()
            if (active && GL.getCapabilities().OpenGL43) {
                LightBuffer.uploadEmpty()
                ClusterGridBuffer.uploadEmpty()
                ShadowBaker.onShadersDisabled()
            }
        }
    }

    private fun clear() {
        attachments.clear()
        frame.clear()
        identities.clear()
        LightRegistry.clear()
    }

    private fun release() {
        clear()
        ShadowBaker.onShadersDisabled()
        LightBuffer.delete()
        ClusterGridBuffer.delete()
        BlueNoiseTexture.delete()
        LightCookies.close()
    }
}
