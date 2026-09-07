package ru.hollowhorizon.hollowengine.client.models.internal.animator

import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.common.attachments.components.AnimationsComponent
import ru.hollowhorizon.hollowengine.common.models.Animator
import ru.hollowhorizon.hollowengine.common.models.AnimatorLayerSpec

/**
 * Animator for one model instance: a stack of layers, blended in priority order.
 */
class ModelAnimator {
    private val specLayers = LinkedHashMap<String, SpecLayer>()
    private val clientLayers = LinkedHashMap<String, AnimationLayer>()
    private var model: Animator? = null
    private var animations: AnimationsComponent? = null
    private var lastTicks: Float = Float.NaN

    /**
     * Sets the layers this animator runs.
     *
     * [model] is the animator the model wears, [animations] is what gameplay asked this entity to play;
     * the clips go on top, and one with the same id replaces a layer of the model's. Layers are reconciled
     * by id, so playback survives an update that leaves a layer's spec alone.
     */
    fun configure(model: Animator?, animations: AnimationsComponent?) {
        if (this.model == model && this.animations == animations) return
        this.model = model
        this.animations = animations
        model?.layers?.let(AnimatorExpressionEvaluator::prepare)
        animations?.clips?.let(AnimatorExpressionEvaluator::prepare)

        val specs = LinkedHashMap<String, AnimatorLayerSpec>()
        model?.layers?.forEach { specs[it.id] = it }
        animations?.clips?.forEach { specs[it.id] = it }
        val rebuilt = LinkedHashMap<String, SpecLayer>(specs.size)
        specs.values.forEach { spec ->
            val existing = specLayers[spec.id]?.takeIf { it.reconfigure(spec) }
            rebuilt[spec.id] = existing ?: AnimatorLayerFactories.create(spec) ?: return@forEach
        }
        specLayers.clear()
        specLayers.putAll(rebuilt)
    }

    /** Adds a layer the server knows nothing about; replaces one with the same id. */
    fun add(layer: AnimationLayer) {
        clientLayers[layer.id] = layer
    }

    fun remove(id: String) {
        clientLayers.remove(id)
    }

    /** Where a layer is inside its animation, for progress bars and the editor. */
    fun layerTime(id: String): Float? = (specLayers[id] ?: clientLayers[id] as? SpecLayer)?.time

    val isEmpty: Boolean get() = specLayers.isEmpty() && clientLayers.isEmpty()

    /**
     * Poses [attachment]'s nodes for this frame.
     */
    fun applyTo(attachment: ModelAttachment, context: AnimatorEvaluationContext) =
        applyTo(attachment.poseTarget(), context)

    fun applyTo(target: PoseTarget, context: AnimatorEvaluationContext) {
        context.deltaTime = deltaSeconds(context.time)
        if (isEmpty) return

        val layers = (specLayers.values + clientLayers.values).sortedBy { it.priority }
        layers.forEach { layer ->
            val weight = layer.weight(context)
            if (weight <= 0f) return@forEach

            val sampled = layer.sample(target, context) ?: return@forEach
            val finalWeight = weight * sampled.weightScale
            if (finalWeight <= 0f) return@forEach

            target.apply(sampled.pose, layer.blendMode, finalWeight, sampled.reference)
        }

        specLayers.values.removeIf(AnimationLayer::finished)
        clientLayers.values.removeIf(AnimationLayer::finished)
    }

    /**
     * Seconds since the last frame, from the tick clock.
     */
    private fun deltaSeconds(nowTicks: Float): Float {
        val previous = lastTicks
        lastTicks = nowTicks
        if (previous.isNaN()) return 0f
        return ((nowTicks - previous) / TICKS_PER_SECOND).coerceIn(0f, 1f)
    }

    private companion object {
        const val TICKS_PER_SECOND = 20f
    }
}
