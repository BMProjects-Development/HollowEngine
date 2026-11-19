package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.models.ModelAttachmentSpec

/** Retains nested model scenes whose owners survive a model or rig rebuild. */
internal class ModelNestedAttachments(nodes: List<RenderModelNode>, modelRoot: RenderModelNode?) : AutoCloseable {
    private val retained = ArrayList<NestedModelAttachment>()

    init {
        fun retain(node: RenderModelNode) {
            val iterator = node.attachments.iterator()
            while (iterator.hasNext()) {
                val attachment = iterator.next() as? NestedModelAttachment ?: continue
                iterator.remove()
                retained.add(attachment)
            }
        }
        nodes.forEach(::retain)
        modelRoot?.let(::retain)
    }

    fun reuse(spec: Any, context: RigAttachmentContext): Attachment? {
        if (spec !is ModelAttachmentSpec) return null
        val index = retained.indexOfFirst { existing ->
            existing.spec.id == spec.id && existing.spec.model == spec.model &&
                !existing.model.sceneRoot.isRemoved && existing.model.sceneRoot.physicalParent === context.node.sceneObject
        }
        if (index < 0) return null
        val attachment = retained.removeAt(index)
        try {
            attachment.rebind(context, spec)
            return attachment
        } catch (exception: Exception) {
            retained.add(attachment)
            throw exception
        }
    }

    override fun close() {
        var failure: Exception? = null
        retained.forEach { attachment ->
            HollowEngine.LOGGER.warn("Nested model '{}' was removed during model rebuild", attachment.spec.id)
            try {
                attachment.close()
            } catch (exception: Exception) {
                val previous = failure
                if (previous == null) failure = exception else if (previous !== exception) previous.addSuppressed(exception)
            }
        }
        retained.clear()
        failure?.let { throw it }
    }
}
