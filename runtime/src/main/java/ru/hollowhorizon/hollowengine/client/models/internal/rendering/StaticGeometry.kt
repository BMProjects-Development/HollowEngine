package ru.hollowhorizon.hollowengine.client.models.internal.rendering

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.ShaderInstance
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.Primitive
import ru.hollowhorizon.hollowengine.client.models.internal.utils.VboWrapper
import ru.hollowhorizon.hollowengine.fabric.internal.IrisHelper
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** One unskinned primitive on one node of a model, as it sits in [StaticGeometry]. */
class StaticSlot(
    val node: Int,
    val primitive: Primitive,
    val index: Int,
    val local: Int,
    val firstIndex: Int,
    val indexCount: Int,
)

/** The slots a model gives one material: one range of indices, drawn by one call for every instance. */
class StaticPart(
    val geometry: StaticGeometry,
    val material: Material,
    val slots: List<StaticSlot>,
    val firstIndex: Int,
    val indexCount: Int,
)

/**
 * The unskinned primitives of a model merged into one buffer, so that all instances of the model draw them at once.
 *
 * Every vertex stays in the space of its node and carries the index of its slot; the matrix of each slot of each
 * instance comes from [StaticInstanceData]. Slots are grouped into [parts] by the material the model gives them.
 */
class StaticGeometry private constructor(model: Model) {
    val slots: List<StaticSlot>
    val parts: List<StaticPart>

    private val slotsByNode = HashMap<SlotKey, StaticSlot>()
    private val vertices = VboWrapper.createArrayBuffer()
    private val indices = VboWrapper.createElementBuffer()
    private val bindings = IdentityHashMap<ShaderInstance, ShaderBinding>()
    private var fixedVao = -1

    init {
        val entries = model.nodes.flatMap { node ->
            node.mesh?.primitives.orEmpty().filter { !it.isDynamic }.map { node.index to it }
        }
        entries.forEach { (_, primitive) -> primitive.prepareGeometry() }

        val vertexCount = entries.sumOf { (_, primitive) -> primitive.positions?.size ?: 0 }
        val indexCount = entries.sumOf { (_, primitive) -> primitive.elementCount() }
        val vertexData = BufferUtils.createFloatBuffer(vertexCount * FLOATS_PER_VERTEX)
        val indexData = BufferUtils.createIntBuffer(indexCount)

        val allSlots = ArrayList<StaticSlot>()
        val allParts = ArrayList<StaticPart>()
        var baseVertex = 0
        entries.groupBy { (_, primitive) -> primitive.material }.forEach { (material, group) ->
            val partFirstIndex = indexData.position()
            val partSlots = group.mapIndexed { local, (node, primitive) ->
                val firstIndex = indexData.position()
                primitive.putVertices(vertexData, local)
                primitive.putIndices(indexData, baseVertex)
                baseVertex += primitive.positions?.size ?: 0
                StaticSlot(node, primitive, allSlots.size, local, firstIndex, indexData.position() - firstIndex)
                    .also { allSlots += it; slotsByNode[SlotKey(node, primitive)] = it }
            }
            allParts += StaticPart(this, material, partSlots, partFirstIndex, indexData.position() - partFirstIndex)
        }
        slots = allSlots
        parts = allParts
        entries.forEach { (_, primitive) -> primitive.releaseCpu() }

        vertexData.flip()
        indexData.flip()
        vertices.uploadData(vertexData)
        indices.uploadData(indexData, bindingTarget = GL33.GL_ARRAY_BUFFER)
        GEOMETRIES += this
    }

    /** The slot of [primitive] on the node [node] of the model, or null when it is drawn on its own. */
    fun slotOf(node: Int, primitive: Primitive): StaticSlot? = slotsByNode[SlotKey(node, primitive)]

    /**
     * Draws [instances] of [part] with one call. [shader] reads the matrices of the slots from the buffer texture,
     * as the engine's instanced shader and the programs [IrisInstancingShaderPatcher] makes do.
     */
    fun drawInstanced(part: StaticPart, material: Material, shader: ShaderInstance, instances: List<StaticInstance>, layout: InstancedShaderLayoutMode) {
        val binding = bindings.getOrPut(shader) { bind(shader) }
        StaticInstanceData.upload(part, instances, layout)
        material.bind(MaterialBindContext(shader, binding.colorLocation))

        if (binding.slotCountLocation != -1) GL33.glUniform1i(binding.slotCountLocation, part.slots.size)
        StaticInstanceData.bindTransforms(binding.transformsLocation)
        RenderSystem.glBindVertexArray(binding.vao)
        GL33.glDrawElementsInstanced(
            GL33.GL_TRIANGLES, part.indexCount, GL33.GL_UNSIGNED_INT, part.firstIndex * Int.SIZE_BYTES.toLong(), instances.size,
        )
        RenderSystem.glBindVertexArray(0)
        StaticInstanceData.unbindTransforms()
    }

    /**
     * Draws [instances] of [part] slot by slot with a shader that takes one model-view matrix, for when a shader pack
     * program could not be made to read the buffer texture.
     */
    fun drawEach(part: StaticPart, material: Material, shader: ShaderInstance, instances: List<StaticInstance>, layout: InstancedShaderLayoutMode) {
        material.bind(MaterialBindContext(shader, FIXED_COLOR_LOCATION))
        RenderSystem.glBindVertexArray(fixedVao())
        val normalUniform = shader.getUniform("NormalMat")
        instances.forEach { instance ->
            GL33.glVertexAttribI2i(FIXED_OVERLAY_LOCATION, instance.overlay and FFFF, instance.overlay shr 16 and FFFF)
            GL33.glVertexAttribI2i(FIXED_LIGHT_LOCATION, instance.light and FFFF, instance.light shr 16 and FFFF)
            if (ENTITY_LOCATION != -1) {
                GL33.glVertexAttribI3i(ENTITY_LOCATION, instance.entityInfo.entity, instance.entityInfo.blockEntity, instance.entityInfo.item)
            }
            part.slots.forEach { slot ->
                if (!instance.drawn[slot.local]) return@forEach
                val transform = instance.slotTransform(slot.local, layout)
                shader.MODEL_VIEW_MATRIX?.set(transform.modelView)
                shader.MODEL_VIEW_MATRIX?.upload()
                normalUniform?.set(transform.normal)
                normalUniform?.upload()
                GL33.glDrawElements(GL33.GL_TRIANGLES, slot.indexCount, GL33.GL_UNSIGNED_INT, slot.firstIndex * Int.SIZE_BYTES.toLong())
            }
        }
        RenderSystem.glBindVertexArray(0)
    }

    private fun bind(shader: ShaderInstance): ShaderBinding {
        val previousVao = GL33.glGetInteger(GL33.GL_VERTEX_ARRAY_BINDING)
        val vao = GL33.glGenVertexArrays()
        GL33.glBindVertexArray(vao)
        vertices.bind()
        fun attribute(name: String, size: Int, offset: Int) =
            pointTo(GL33.glGetAttribLocation(shader.id, name), size, offset)
        attribute("Position", 3, POSITION_OFFSET)
        attribute("Normal", 3, NORMAL_OFFSET)
        attribute("UV0", 2, UV_OFFSET)
        attribute("mc_midTexCoord", 2, MID_OFFSET)
        attribute("at_tangent", 4, TANGENT_OFFSET)
        attribute(SLOT_ATTRIBUTE, 1, SLOT_OFFSET)
        StaticInstanceData.pointInstanceAttributes(shader)
        indices.bind()
        GL33.glBindVertexArray(previousVao)

        return ShaderBinding(
            vao = vao,
            colorLocation = firstAttributeLocation(shader, "Color", "iris_Color"),
            slotCountLocation = GL33.glGetUniformLocation(shader.id, SLOT_COUNT_UNIFORM),
            transformsLocation = GL33.glGetUniformLocation(shader.id, TRANSFORMS_UNIFORM),
        )
    }

    /** Attributes at the locations a vanilla or Iris entity shader is linked with, for [drawEach]. */
    private fun fixedVao(): Int {
        if (fixedVao != -1) return fixedVao
        val previousVao = GL33.glGetInteger(GL33.GL_VERTEX_ARRAY_BINDING)
        fixedVao = GL33.glGenVertexArrays()
        GL33.glBindVertexArray(fixedVao)
        vertices.bind()
        pointTo(FIXED_POSITION_LOCATION, 3, POSITION_OFFSET)
        pointTo(FIXED_NORMAL_LOCATION, 3, NORMAL_OFFSET)
        pointTo(FIXED_UV_LOCATION, 2, UV_OFFSET)
        pointTo(MID_TEX_COORD_LOCATION, 2, MID_OFFSET)
        pointTo(TANGENT_LOCATION, 4, TANGENT_OFFSET)
        indices.bind()
        GL33.glBindVertexArray(previousVao)
        return fixedVao
    }

    private fun pointTo(location: Int, size: Int, offset: Int) {
        if (location == -1) return
        GL33.glVertexAttribPointer(location, size, GL33.GL_FLOAT, false, STRIDE_BYTES, offset.toLong())
        GL33.glEnableVertexAttribArray(location)
    }

    private fun clearBindings() {
        bindings.values.forEach { GL33.glDeleteVertexArrays(it.vao) }
        bindings.clear()
    }

    fun destroy() {
        clearBindings()
        if (fixedVao != -1) GL33.glDeleteVertexArrays(fixedVao)
        fixedVao = -1
        vertices.delete()
        indices.delete()
        GEOMETRIES -= this
    }

    private data class SlotKey(val node: Int, val primitive: Primitive)

    private class ShaderBinding(val vao: Int, val colorLocation: Int, val slotCountLocation: Int, val transformsLocation: Int)

    companion object {
        /** What the vertex shader calls the slot index of a vertex, the size of an instance and the matrix buffer. */
        const val SLOT_ATTRIBUTE = "_he_Slot"
        const val SLOT_COUNT_UNIFORM = "_he_SlotCount"
        const val TRANSFORMS_UNIFORM = "_he_Transforms"

        private const val FLOATS_PER_VERTEX = 15
        private const val STRIDE_BYTES = FLOATS_PER_VERTEX * Float.SIZE_BYTES
        private const val POSITION_OFFSET = 0
        private const val NORMAL_OFFSET = 3 * Float.SIZE_BYTES
        private const val UV_OFFSET = 6 * Float.SIZE_BYTES
        private const val MID_OFFSET = 8 * Float.SIZE_BYTES
        private const val TANGENT_OFFSET = 10 * Float.SIZE_BYTES
        private const val SLOT_OFFSET = 14 * Float.SIZE_BYTES

        private const val FFFF = '￿'.code
        private const val FIXED_POSITION_LOCATION = 0
        private const val FIXED_COLOR_LOCATION = 1
        private const val FIXED_UV_LOCATION = 2
        private const val FIXED_OVERLAY_LOCATION = 3
        private const val FIXED_LIGHT_LOCATION = 4
        private const val FIXED_NORMAL_LOCATION = 5
        private val TANGENT_LOCATION = IrisHelper.entityAttributeLocation("at_tangent")
        private val MID_TEX_COORD_LOCATION = IrisHelper.entityAttributeLocation("mc_midTexCoord")
        private val ENTITY_LOCATION = IrisHelper.entityAttributeLocation("iris_Entity")

        private val GEOMETRIES = Collections.newSetFromMap(WeakHashMap<StaticGeometry, Boolean>())

        /** Merges the unskinned primitives of [model]; null when it has none. Uploads, so on the render thread only. */
        fun of(model: Model): StaticGeometry? {
            RenderSystem.assertOnRenderThread()
            val hasStatic = model.nodes.any { node -> node.mesh?.primitives.orEmpty().any { !it.isDynamic } }
            return if (hasStatic) StaticGeometry(model) else null
        }

        /** Forgets the vertex arrays made for shader programs, after the programs were replaced. */
        fun invalidateBindings() {
            GEOMETRIES.toList().forEach(StaticGeometry::clearBindings)
        }
    }
}

internal fun firstAttributeLocation(shader: ShaderInstance, vararg names: String): Int {
    for (name in names) {
        val location = GL33.glGetAttribLocation(shader.id, name)
        if (location != -1) return location
    }
    return -1
}

private fun Primitive.elementCount(): Int = indices?.size ?: (positions?.size ?: 0)

private fun Primitive.putVertices(data: FloatBuffer, slot: Int) {
    val positions = positions ?: return
    val normals = normals
    val uvs = texCoords
    val mids = midCoords
    val tangents = tangents
    for (i in positions.indices) {
        val position = positions[i]
        data.put(position.x).put(position.y).put(position.z)
        normals?.get(i)?.let { data.put(it.x).put(it.y).put(it.z) } ?: data.put(0f).put(1f).put(0f)
        uvs?.get(i)?.let { data.put(it.x).put(it.y) } ?: data.put(0f).put(0f)
        mids?.get(i)?.let { data.put(it.x).put(it.y) } ?: data.put(0f).put(0f)
        tangents?.get(i)?.let { data.put(it.x).put(it.y).put(it.z).put(it.w) } ?: data.put(1f).put(0f).put(0f).put(1f)
        data.put(slot.toFloat())
    }
}

private fun Primitive.putIndices(data: IntBuffer, baseVertex: Int) {
    val count = positions?.size ?: 0
    val indices = indices
    if (indices == null) {
        for (i in 0 until count) data.put(baseVertex + i)
    } else {
        indices.forEach { data.put(baseVertex + it) }
    }
}
