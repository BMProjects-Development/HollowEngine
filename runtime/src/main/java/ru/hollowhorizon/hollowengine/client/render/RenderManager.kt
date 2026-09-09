package ru.hollowhorizon.hollowengine.client.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.entity.LivingEntityRenderer
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import org.joml.Quaternionf
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.fillAnimationVariables
import ru.hollowhorizon.hollowengine.client.models.internal.hostYawDegrees
import ru.hollowhorizon.hollowengine.client.models.internal.manager.AnimatorAssets
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.InstanceBatchManager
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderContext
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.modelInstance
import ru.hollowhorizon.hollowengine.common.attachments.binding.NodeRuntimeState
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderEntityEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderLevelStageEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderPlayerEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderStage
import ru.hollowhorizon.hollowengine.common.models.StandardPlayerAnimatorPreset
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.util.*

@ClientOnly
object RenderManager {
    private val modelCullingBounds = IdentityHashMap<Entity, AABB>()
    private val frustumCullingDisabledHosts = Collections.newSetFromMap(IdentityHashMap<Entity, Boolean>())

    fun onInitialize() {
        HollowModelManager.initialize()
        AnimatorAssets.register(StandardPlayerAnimatorPreset.ID.rl, StandardPlayerAnimatorPreset.create())
    }

    private var isWorldPass = false

    @SubscribeEvent
    fun onTrackWorldPass(event: RenderLevelStageEvent) {
        isWorldPass = event.stage != RenderStage.AFTER_LEVEL
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
     * Advances every model animation once, before anything is drawn with it.
     */
    @SubscribeEvent(10)
    fun onPrepareModelFrames(event: RenderLevelStageEvent) {
        if (event.stage != RenderStage.AFTER_SKY) return

        modelCullingBounds.clear()
        frustumCullingDisabledHosts.clear()
        val level = Minecraft.getInstance().level ?: return
        val partialTick = event.partialTick

        NodeRuntimeState.service(level).forEachModelNodeRecord { record, node ->
            val host = record.hostEntity ?: return@forEachModelNodeRecord
            val instance = host.modelInstance(node.nodeId, node.model.model)

            instance.attachment.entity = host as? LivingEntity
            instance.configure(node.animations, node.materials)
            val worldTransform = resolveNodeWorldTransform(host, node.transform, partialTick)
            instance.update(
                AnimatorEvaluationContext().also {
                    fillAnimationVariables(it, host, partialTick)
                    it.modelToWorld = worldTransform
                }
            )

            if (!instance.attachment.isFrustumCullingEnabled) {
                frustumCullingDisabledHosts.add(host)
                return@forEachModelNodeRecord
            }

            val localBounds = instance.attachment.calculateBounds() ?: return@forEachModelNodeRecord
            val worldBounds = buildNodeRenderBounds(localBounds, worldTransform)
            modelCullingBounds.merge(host, worldBounds) { current, added -> current.minmax(added) }
        }
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
        val openedBatchedRenderTypes = LinkedHashSet<RenderType>()
        val allowInstancing = isWorldPass && shouldAllowInstancingInCurrentPass()
        var renderedAny = false

        materialization.forEachModelNodeOf(entity) { _, node ->
            val instance = entity.modelInstance(node.nodeId, node.model.model)
            val attachment = instance.attachment
            attachment.entity = entity as? LivingEntity
            instance.configure(node.animations, node.materials)
            val worldTransform = resolveNodeWorldTransform(entity, node.transform, partialTick)
            instance.update(
                AnimatorEvaluationContext().also {
                    fillAnimationVariables(it, entity, partialTick)
                    it.modelToWorld = worldTransform
                }
            )
            val light = lightAt(level, entity, attachment, worldTransform, packedLight)

            val hostYaw = when (entity) {
                is LivingEntity -> Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot)
                else -> Mth.rotLerp(partialTick, entity.yRotO, entity.yRot)
            }
            val local = node.transform.transform
            poseStack.pushPose()
            poseStack.mulPose(Axis.YP.rotationDegrees(hostYawDegrees(hostYaw)))
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
                    openedBatchedRenderTypes = openedBatchedRenderTypes,
                )
            )
            if (DebugSkeletonRenderer.isEnabled) {
                DebugSkeletonRenderer.render(attachment, poseStack, bufferSource)
            }
            poseStack.popPose()
            renderedAny = true
        }

        if (renderedAny && !isWorldPass) {
            (bufferSource as? MultiBufferSource.BufferSource)
                ?.let { flushable -> openedBatchedRenderTypes.forEach(flushable::endBatch) }
        }
    }

    private fun lightAt(
        level: Level,
        entity: Entity,
        attachment: ModelAttachment,
        worldTransform: TrsTransformF,
        packedLight: Int,
    ): Int {
        val (min, max) = attachment.calculateBounds() ?: return packedLight
        val centre = MutableVec3f()
        worldTransform.matrixF.transform((min + max) * 0.5f, 1f, centre)

        val position = BlockPos.containing(centre.x.toDouble(), centre.y.toDouble(), centre.z.toDouble())
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
