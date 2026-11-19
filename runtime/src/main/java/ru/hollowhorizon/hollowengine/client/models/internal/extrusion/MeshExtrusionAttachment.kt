package ru.hollowhorizon.hollowengine.client.models.internal.extrusion

import com.mojang.blaze3d.systems.RenderSystem
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderContext
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderPipeline
import ru.hollowhorizon.hollowengine.client.models.internal.v2.BaseAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.MeshAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RespecAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RigAttachmentContext
import ru.hollowhorizon.hollowengine.common.models.MeshExtrusionSpec
import kotlin.math.abs

internal class MeshExtrusionAttachment private constructor(
    override var spec: MeshExtrusionSpec,
    private val context: RigAttachmentContext,
    meshes: List<MeshAttachment>,
) : BaseAttachment(context.node), RespecAttachment {
    private val layers = meshes.map(::Layer)
    val boundsMargin: Float get() = abs(spec.thickness) * ExtrusionMesh.MITER_LIMIT

    override fun respec(spec: Any) {
        this.spec = spec as MeshExtrusionSpec
    }

    override fun collectCommands(pipeline: RenderPipeline) {
        pipeline.requireImmediateMeshes()
        pipeline.addPreparation {
            if (context.node.isVisible) layers.forEach(Layer::prepare)
        }
        if (layers.any { it.source.primitive.isDynamic }) {
            pipeline.addSkinnable { layers.forEach(Layer::deform) }
        }
        pipeline.addVAORenderable { layers.forEach { it.draw(this) } }
    }

    override fun close() {
        if (RenderSystem.isOnRenderThread()) layers.forEach(Layer::release)
        else RenderSystem.recordRenderCall { layers.forEach(Layer::release) }
    }

    private inner class Layer(val source: MeshAttachment) {
        private var prepared: Key? = null
        private var mesh: MeshAttachment? = null

        fun prepare() {
            val texture = if (spec.thickness != 0f) MaterialTexelMasks.texture(source.material) else null
            val key = Key(texture, MaterialTexelMasks.revision, spec.thickness)
            if (prepared == key) return
            if (texture == null) {
                release()
                prepared = key
                return
            }

            val mask = MaterialTexelMasks.get(texture)
            if (mask == null) {
                release()
                if (MaterialTexelMasks.isReady(texture)) prepared = key
                return
            }
            val primitive = ExtrusionMesh.build(source.primitive, mask, key.thickness)
            val next = primitive?.takeIf { it.positionsCount > 0 }?.let {
                MeshAttachment(it, context.node, source.material).also {
                    it.updateGlobalMatrix()
                    primitive.standaloneRenderer()
                }
            }
            mesh?.primitive?.destroy()
            mesh = next
            prepared = key
            replaceSource(primitive != null)
        }

        fun deform() {
            val mesh = mesh ?: return
            mesh.updateGlobalMatrix()
            mesh.primitive.standaloneRenderer().deform(mesh)
        }

        fun draw(context: RenderContext) {
            val mesh = mesh ?: return
            mesh.updateGlobalMatrix()
            mesh.primitive.standaloneRenderer().draw(context, mesh)
        }

        fun release() {
            mesh?.primitive?.destroy()
            mesh = null
            replaceSource(false)
        }

        private fun replaceSource(replaced: Boolean) {
            if (source.replaced == replaced) return
            source.replaced = replaced
            context.owner?.appearanceChanged()
        }
    }

    private data class Key(val texture: MaterialTexelMasks.Texture?, val revision: Int, val thickness: Float)

    companion object {
        fun create(spec: MeshExtrusionSpec, context: RigAttachmentContext): MeshExtrusionAttachment? {
            val attachments = context.node.attachments
            if (attachments.any { it is MeshExtrusionAttachment }) return null
            val meshes = attachments.filterIsInstance<MeshAttachment>().filter { it.primitive.supportsExtrusion }
            if (meshes.isEmpty()) return null
            return MeshExtrusionAttachment(spec, context, meshes)
        }
    }
}
