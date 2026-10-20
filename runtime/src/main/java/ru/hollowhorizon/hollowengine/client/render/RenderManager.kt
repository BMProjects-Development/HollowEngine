package ru.hollowhorizon.hollowengine.client.render

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import org.joml.Quaternionf
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.fillAnimationVariables
import ru.hollowhorizon.hollowengine.client.models.internal.manager.AnimatorAssets
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.InstanceBatchManager
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderContext
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelInstance
import ru.hollowhorizon.hollowengine.client.models.internal.v2.modelInstance
import ru.hollowhorizon.hollowengine.common.attachments.binding.NodeRuntimeState
import ru.hollowhorizon.hollowengine.common.colliders.hostRotation
import ru.hollowhorizon.hollowengine.common.colliders.hostScale
import ru.hollowhorizon.hollowengine.common.colliders.resolveNodeWorldTransform
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderEntityEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderLevelStageEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderPlayerEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderStage
import ru.hollowhorizon.hollowengine.common.models.StandardPlayerAnimatorPreset
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.fabric.internal.IrisHelper
import java.util.*

@ClientOnly
object RenderManager {
    /** How far a pose may reach past the bounds of the last one, while a model out of view keeps it. */
    private const val POSE_MARGIN = 1.0

    /** Nearer than this a model is posed every frame; farther, at most as often as the rates below allow. */
    private const val EVERY_FRAME_DISTANCE = 8.0
    private const val FAST_DISTANCE = 32.0
    private const val SLOW_DISTANCE = 64.0
    private const val FAST_POSE_NANOS = 1_000_000_000L / 60
    private const val SLOW_POSE_NANOS = 1_000_000_000L / 30
    private const val FAR_POSE_NANOS = 1_000_000_000L / 15

    /** Shifts when each entity's model is posed, so the models of a crowd do not all pose in the same frame. */
    private const val POSE_PHASE_NANOS = 7_368_787L

    private var frameNanos = 0L
    private var frameNanosOf = Long.MIN_VALUE

    private val modelCullingBounds = IdentityHashMap<Entity, AABB>()
    private val frustumCullingDisabledHosts = Collections.newSetFromMap(IdentityHashMap<Entity, Boolean>())

    fun onInitialize() {
        HollowModelManager.initialize()
        AnimatorAssets.register(StandardPlayerAnimatorPreset.ID.rl, StandardPlayerAnimatorPreset.create())
    }

    /** Whether the stages of the level are running: from the sky to the end of the level. */
    private var isLevelStage = false

    /**
     * Whether entities are being drawn into the level now, rather than into an interface. Iris draws its shadows
     * before the sky, outside the level stages, so its shadow pass counts here on its own.
     */
    val isWorldPass: Boolean get() = isLevelStage || isShadowPass

    /**
     * Whether a shader pack draws the level from its light now. Models drawn then are not where the camera sees
     * them, so nothing should remember their placement for the frame.
     */
    val isShadowPass: Boolean get() = IrisHelper.isShadowRendering()

    @SubscribeEvent
    fun onTrackWorldPass(event: RenderLevelStageEvent) {
        isLevelStage = event.stage != RenderStage.AFTER_LEVEL
    }

    @SubscribeEvent
    fun onRenderInstanced(event: RenderLevelStageEvent) {
        when (event.stage) {
            RenderStage.AFTER_ENTITIES -> {
                InstanceBatchManager.flush()
                InstanceBatchManager.clear()
            }

            RenderStage.AFTER_LEVEL -> {
                InstanceBatchManager.flush()
                InstanceBatchManager.clear()
            }

            else -> {}
        }
    }

    /**
     * Advances the model animations once a frame, before anything is drawn with them.
     *
     * A model out of view keeps its last pose: placed where its entity stands now, the bounds of that pose say
     * whether it is seen, and whatever draws it after all, as a shader pack's shadow pass, poses it then. Far
     * models are posed at a lower rate; see [posesThisFrame].
     */
    @SubscribeEvent(10)
    fun onPrepareModelFrames(event: RenderLevelStageEvent) {
        if (event.stage != RenderStage.AFTER_SKY) return

        modelCullingBounds.clear()
        frustumCullingDisabledHosts.clear()
        val level = Minecraft.getInstance().level ?: return
        val partialTick = event.partialTick
        val frustum = event.frustum

        NodeRuntimeState.service(level).forEachModelNodeRecord { record, node ->
            val host = record.hostEntity ?: return@forEachModelNodeRecord
            val instance = host.modelInstance(node.nodeId, node.model.model)

            instance.attachment.entity = host as? LivingEntity
            instance.attachment.carrier = host
            instance.configure(node.animations, node.materials, node.model.rig)
            val worldTransform = resolveNodeWorldTransform(host, node.transform, partialTick)
            val culled = instance.attachment.isFrustumCullingEnabled && !instance.attachment.carriesEffects

            val lastBounds = if (culled && instance.hasPose) instance.attachment.cullingBounds() else null
            val lastWorldBounds = lastBounds?.let { buildNodeRenderBounds(it, worldTransform) }
            val hidden = frustum != null && lastWorldBounds != null && !frustum.isVisible(lastWorldBounds.inflate(POSE_MARGIN))
            if (!hidden && posesThisFrame(instance, host, culled)) {
                instance.update(animationContext(host, partialTick, worldTransform))
            }

            instance.attachment.updateWorldPose(worldTransform, host)

            if (!culled) {
                frustumCullingDisabledHosts.add(host)
                return@forEachModelNodeRecord
            }

            val localBounds = instance.attachment.cullingBounds() ?: return@forEachModelNodeRecord
            val worldBounds = lastWorldBounds?.takeIf { localBounds === lastBounds } ?: buildNodeRenderBounds(localBounds, worldTransform)
            modelCullingBounds.merge(host, worldBounds) { current, added -> current.minmax(added) }
        }
    }

    /**
     * Whether [instance] of [host] is posed this frame or keeps the pose it has. A model posed before, that
     * [mayThin], is posed every frame up close and at a capped rate farther away, whatever the frame rate is:
     * the entity still moves every frame, only its bones wait.
     */
    private fun posesThisFrame(instance: ModelInstance, host: Entity, mayThin: Boolean): Boolean {
        if (!mayThin || !instance.hasPose) return true
        val camera = Minecraft.getInstance().gameRenderer.mainCamera.position
        val distanceSqr = host.distanceToSqr(camera)
        val interval = when {
            distanceSqr < EVERY_FRAME_DISTANCE * EVERY_FRAME_DISTANCE -> return true
            distanceSqr < FAST_DISTANCE * FAST_DISTANCE -> FAST_POSE_NANOS
            distanceSqr < SLOW_DISTANCE * SLOW_DISTANCE -> SLOW_POSE_NANOS
            else -> FAR_POSE_NANOS
        }
        val period = (currentFrameNanos() + host.id * POSE_PHASE_NANOS) / interval
        if (period == instance.posePeriod) return false
        instance.posePeriod = period
        return true
    }

    /** One clock reading for the whole frame, so every model of it is judged by the same time. */
    private fun currentFrameNanos(): Long {
        if (frameNanosOf != TickHandler.renderFrame) {
            frameNanosOf = TickHandler.renderFrame
            frameNanos = System.nanoTime()
        }
        return frameNanos
    }

    private fun animationContext(host: Entity, partialTick: Float, worldTransform: TrsTransformF) =
        AnimatorEvaluationContext().also {
            fillAnimationVariables(it, host, partialTick)
            it.modelToWorld = worldTransform
        }

    fun extendCullingBounds(entity: Entity, vanillaBounds: AABB): AABB =
        modelCullingBounds[entity]?.let(vanillaBounds::minmax) ?: vanillaBounds

    fun isFrustumCullingDisabled(entity: Entity): Boolean = entity in frustumCullingDisabledHosts

    @SubscribeEvent
    fun onRenderEntityNodes(event: RenderEntityEvent.Pre) {
        renderHostedNodes(event.entity, event.partialTicks, event.poseStack, event.buffer, event.packedLight)
    }

    @SubscribeEvent
    fun onRenderPlayerNodes(event: RenderPlayerEvent) {
        renderHostedNodes(event.player, event.partialTicks, event.poseStack, event.buffer, event.packedLight)
    }

    private fun renderHostedNodes(
        entity: Entity,
        partialTick: Float,
        poseStack: PoseStack,
        bufferSource: MultiBufferSource,
        packedLight: Int,
    ) {
        val level = Minecraft.getInstance().level ?: return
        val materialization = NodeRuntimeState.service(level)
        val allowInstancing = isWorldPass && shouldAllowInstancingInCurrentPass()

        materialization.forEachModelNodeOf(entity) { _, node ->
            val instance = entity.modelInstance(node.nodeId, node.model.model)
            val attachment = instance.attachment
            attachment.entity = entity as? LivingEntity
            attachment.carrier = entity
            instance.configure(node.animations, node.materials, node.model.rig)
            val worldTransform = resolveNodeWorldTransform(entity, node.transform, partialTick)
            val thinned = isWorldPass && attachment.isFrustumCullingEnabled && !attachment.carriesEffects
            if (posesThisFrame(instance, entity, thinned)) instance.update(animationContext(entity, partialTick, worldTransform))
            val light =
                if (isWorldPass) lightAt(level, entity, worldTransform, packedLight) else packedLight

            val hostRotation = hostRotation(entity, partialTick)
            val hostScale = hostScale(entity, partialTick)
            val local = node.transform.transform
            poseStack.pushPose()
            if (isWorldPass) {
                val shift = hostRenderShift(entity, partialTick)
                poseStack.translate(shift.x, shift.y, shift.z)
            }
            poseStack.mulPose(Quaternionf(hostRotation.x, hostRotation.y, hostRotation.z, hostRotation.w))
            poseStack.scale(hostScale.x, hostScale.y, hostScale.z)
            poseStack.translate(
                local.translation.x.toDouble(),
                local.translation.y.toDouble(),
                local.translation.z.toDouble(),
            )
            poseStack.mulPose(Quaternionf(local.rotation.x, local.rotation.y, local.rotation.z, local.rotation.w))
            poseStack.scale(local.scale.x, local.scale.y, local.scale.z)
            attachment.pipeline.render(
                RenderContext(
                    poseStack,
                    bufferSource,
                    light,
                    (entity as? LivingEntity)?.let { LivingEntityRenderer.getOverlayCoords(it, 0f) }
                        ?: OverlayTexture.NO_OVERLAY,
                    allowInstancing = allowInstancing,
                )
            )
            if (DebugSkeletonRenderer.isEnabled) {
                DebugSkeletonRenderer.render(attachment, poseStack, bufferSource)
            }
            poseStack.popPose()
        }
    }

    /**
     * Light at the node's origin, which follows the host entity. The middle of the mesh bounds is not used:
     * geometry hidden under the floor would drag it into a dark block.
     */
    private fun lightAt(
        level: Level,
        entity: Entity,
        worldTransform: TrsTransformF,
        packedLight: Int,
    ): Int {
        val origin = worldTransform.translation
        val position = BlockPos.containing(origin.x.toDouble(), origin.y.toDouble(), origin.z.toDouble())
        if (position == entity.blockPosition()) return packedLight
        if (!level.getBlockState(position).isAir) {
            val above = position.above()

            return if (!level.getBlockState(above).isAir) packedLight
            else LevelRenderer.getLightColor(level, above)
        }

        return LevelRenderer.getLightColor(level, position)
    }

    private fun shouldAllowInstancingInCurrentPass(): Boolean = true
}
