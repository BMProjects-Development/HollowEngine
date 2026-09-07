package ru.hollowhorizon.hollowengine.client.models.internal.rendering

import com.mojang.blaze3d.systems.RenderSystem
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderInstance
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.models.internal.*
import ru.hollowhorizon.hollowengine.client.models.internal.utils.VboWrapper
import ru.hollowhorizon.hollowengine.client.models.internal.utils.toFloatBuffer
import ru.hollowhorizon.hollowengine.client.models.internal.v2.MeshAttachment
import ru.hollowhorizon.hollowengine.client.utils.areShadersEnabled
import ru.hollowhorizon.hollowengine.client.utils.instancingEntityInfo
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix4f
import ru.hollowhorizon.hollowengine.client.utils.toTexture
import java.util.*
import kotlin.math.abs
import kotlin.math.cbrt

class PipelineRenderer(private val primitive: Primitive) : MeshRenderer {
    private var vao = -1
    private var instancedVao = -1
    private var instancedShaderId = -1
    private var instancedColorLocation = -1
    private val runtimeInstancedBindings = LinkedHashMap<Int, InstancedShaderBinding>()

    private var posBuffer: VboWrapper? = null
    private var norBuffer: VboWrapper? = null
    private var tanBuffer: VboWrapper? = null
    private var uvBuffer: VboWrapper? = null
    private var midBuffer: VboWrapper? = null
    private var indexBuffer: VboWrapper? = null

    private var deformer: GpuDeformer? = null

    private val isDynamic = primitive.hasSkinning || primitive.morphTargets.isNotEmpty()
    private val supportsInstancing = !isDynamic
    private val sortCenter = primitive.localBounds?.let { (min, max) ->
        Vec3f((min.x + max.x) * 0.5f, (min.y + max.y) * 0.5f, (min.z + max.z) * 0.5f)
    } ?: Vec3f.ZERO

    private var instanceModelViewBuffer: VboWrapper? = null
    private var instanceNormalBuffer: VboWrapper? = null
    private var instanceOverlayBuffer: VboWrapper? = null
    private var instanceLightBuffer: VboWrapper? = null
    private var instanceEntityBuffer: VboWrapper? = null
    private var instanceCapacity = 0

    init {
        registerRenderer(this)
    }

    override fun init() {
        vao = GL33.glGenVertexArrays()
        GL33.glBindVertexArray(vao)

        if (isDynamic) {
            initDynamicBuffers()

            deformer = GpuDeformer(primitive)
            deformer?.init(
                dstPos = posBuffer!!.id,
                dstNor = norBuffer!!.id,
                dstTan = tanBuffer!!.id
            )
            GL33.glBindVertexArray(vao)
        } else {
            initStaticBuffers()
        }

        initCommonBuffers()
        if (supportsInstancing) {
            initInstancingBuffers()
            initInstancedVao()
        }

        GL33.glBindVertexArray(0)

        posBuffer?.unbind()
        indexBuffer?.unbind()
    }

    private fun initStaticBuffers() {
        primitive.positions?.let { positions ->
            posBuffer = VboWrapper.createArrayBuffer().apply {
                val data = positions.toFloatBuffer(3) { v, b -> b.put(v.x).put(v.y).put(v.z) }
                uploadData(data)
                GL33.glVertexAttribPointer(0, 3, GL33.GL_FLOAT, false, 0, 0)
                GL33.glEnableVertexAttribArray(0)
            }
        }

        primitive.normals?.let { normals ->
            norBuffer = VboWrapper.createArrayBuffer().apply {
                val data = normals.toFloatBuffer(3) { v, b -> b.put(v.x).put(v.y).put(v.z) }
                uploadData(data)
                GL33.glVertexAttribPointer(5, 3, GL33.GL_FLOAT, false, 0, 0)
                GL33.glEnableVertexAttribArray(5)
            }
        }

        primitive.tangents?.let { tangents ->
            tanBuffer = VboWrapper.createArrayBuffer().apply {
                val data = tangents.toFloatBuffer(4) { v, b -> b.put(v.x).put(v.y).put(v.z).put(v.w) }
                uploadData(data)
                GL33.glVertexAttribPointer(9, 4, GL33.GL_FLOAT, false, 0, 0)
                GL33.glEnableVertexAttribArray(9)
            }
        }
    }

    private fun initDynamicBuffers() {
        val vertexCount = primitive.positionsCount / 3

        posBuffer = VboWrapper.createArrayBuffer().apply {
            allocate(vertexCount * 3 * 4L, GL33.GL_DYNAMIC_COPY)
            GL33.glVertexAttribPointer(0, 3, GL33.GL_FLOAT, false, 0, 0)
            GL33.glEnableVertexAttribArray(0)
        }

        norBuffer = VboWrapper.createArrayBuffer().apply {
            allocate(vertexCount * 3 * 4L, GL33.GL_DYNAMIC_COPY)
            GL33.glVertexAttribPointer(5, 3, GL33.GL_FLOAT, false, 0, 0)
            GL33.glEnableVertexAttribArray(5)
        }

        tanBuffer = VboWrapper.createArrayBuffer().apply {
            allocate(vertexCount * 4 * 4L, GL33.GL_DYNAMIC_COPY)
            GL33.glVertexAttribPointer(8, 4, GL33.GL_FLOAT, false, 0, 0)
            GL33.glEnableVertexAttribArray(8)
        }
    }

    private fun initCommonBuffers() {
        primitive.texCoords?.let { uvs ->
            uvBuffer = VboWrapper.createArrayBuffer().apply {
                val data = uvs.toFloatBuffer(2) { v, b -> b.put(v.x).put(v.y) }
                uploadData(data)
                GL33.glVertexAttribPointer(2, 2, GL33.GL_FLOAT, false, 0, 0)
                GL33.glEnableVertexAttribArray(2)
            }
        }

        primitive.midCoords?.let { midCoords ->
            midBuffer = VboWrapper.createArrayBuffer().apply {
                val data = midCoords.toFloatBuffer(2) { v, b -> b.put(v.x).put(v.y) }
                uploadData(data)
            }
        }

        primitive.indices?.let { indices ->
            indexBuffer = VboWrapper.createElementBuffer().apply {
                val buffer = BufferUtils.createIntBuffer(indices.size)
                buffer.put(indices)
                buffer.flip()
                uploadData(buffer)
            }
        }
    }

    private fun initInstancingBuffers() {
        val initialCapacity = 1

        instanceModelViewBuffer = VboWrapper.createArrayBuffer().apply {
            allocate(initialCapacity * MODEL_VIEW_STRIDE_BYTES.toLong(), GL33.GL_DYNAMIC_DRAW)
        }

        instanceNormalBuffer = VboWrapper.createArrayBuffer().apply {
            allocate(initialCapacity * NORMAL_STRIDE_BYTES.toLong(), GL33.GL_DYNAMIC_DRAW)
        }

        instanceOverlayBuffer = VboWrapper.createArrayBuffer().apply {
            allocate(initialCapacity * INT2_STRIDE_BYTES.toLong(), GL33.GL_DYNAMIC_DRAW)
        }

        instanceLightBuffer = VboWrapper.createArrayBuffer().apply {
            allocate(initialCapacity * INT2_STRIDE_BYTES.toLong(), GL33.GL_DYNAMIC_DRAW)
        }

        instanceEntityBuffer = VboWrapper.createArrayBuffer().apply {
            allocate(initialCapacity * ENTITY_INFO_STRIDE_BYTES.toLong(), GL33.GL_DYNAMIC_DRAW)
        }

        instanceCapacity = initialCapacity
    }

    private fun initInstancedVao(shader: ShaderInstance = INSTANCED_SHADER) {
        if (instancedVao != -1) GL33.glDeleteVertexArrays(instancedVao)
        instancedVao = GL33.glGenVertexArrays()
        instancedShaderId = shader.id
        GL33.glBindVertexArray(instancedVao)

        bindVertexAttribute(shader, "Position", posBuffer, 3, GL33.GL_FLOAT)
        bindVertexAttribute(shader, "UV0", uvBuffer, 2, GL33.GL_FLOAT)
        bindVertexAttribute(shader, "Normal", norBuffer, 3, GL33.GL_FLOAT)

        instanceModelViewBuffer?.bind()
        for (i in 0 until 4) {
            val location = GL33.glGetAttribLocation(shader.id, "InstanceModelView$i")
            if (location == -1) continue
            GL33.glVertexAttribPointer(location, 4, GL33.GL_FLOAT, false, MODEL_VIEW_STRIDE_BYTES, (i * 16).toLong())
            GL33.glEnableVertexAttribArray(location)
            GL33.glVertexAttribDivisor(location, 1)
        }

        instanceNormalBuffer?.bind()
        for (i in 0 until 3) {
            val location = GL33.glGetAttribLocation(shader.id, "InstanceNormal$i")
            if (location == -1) continue
            GL33.glVertexAttribPointer(location, 3, GL33.GL_FLOAT, false, NORMAL_STRIDE_BYTES, (i * 12).toLong())
            GL33.glEnableVertexAttribArray(location)
            GL33.glVertexAttribDivisor(location, 1)
        }

        bindIntegerAttribute(shader, "InstanceUV1", instanceOverlayBuffer, 2, GL33.GL_INT, INT2_STRIDE_BYTES, 1)
        bindIntegerAttribute(shader, "InstanceUV2", instanceLightBuffer, 2, GL33.GL_INT, INT2_STRIDE_BYTES, 1)
        instancedColorLocation = firstAttribLocation(shader.id, "Color")

        indexBuffer?.bind()
        GL33.glBindVertexArray(0)
    }

    private fun getInstancedBinding(shader: ShaderInstance, layoutMode: InstancedShaderLayoutMode): InstancedShaderBinding {
        if (layoutMode == InstancedShaderLayoutMode.FIXED) {
            if (instancedShaderId != shader.id) initInstancedVao(shader)
            return InstancedShaderBinding(instancedVao, instancedColorLocation)
        }

        return runtimeInstancedBindings.getOrPut(shader.id) {
            createRuntimeInstancedBinding(shader)
        }
    }

    private fun createRuntimeInstancedBinding(shader: ShaderInstance): InstancedShaderBinding {
        val runtimeVao = GL33.glGenVertexArrays()
        GL33.glBindVertexArray(runtimeVao)

        bindVertexAttribute(shader, "Position", posBuffer, 3, GL33.GL_FLOAT)
        bindVertexAttribute(shader, "UV0", uvBuffer, 2, GL33.GL_FLOAT)
        bindVertexAttribute(shader, "Normal", norBuffer, 3, GL33.GL_FLOAT)
        bindVertexAttribute(shader, "at_tangent", tanBuffer, 4, GL33.GL_FLOAT)
        bindVertexAttribute(shader, "mc_midTexCoord", midBuffer ?: uvBuffer, 2, GL33.GL_FLOAT)

        instanceModelViewBuffer?.bind()
        for (i in 0 until 4) {
            val location = GL33.glGetAttribLocation(shader.id, "_he_InstanceModelView$i")
            if (location == -1) continue
            GL33.glVertexAttribPointer(location, 4, GL33.GL_FLOAT, false, MODEL_VIEW_STRIDE_BYTES, (i * 16).toLong())
            GL33.glEnableVertexAttribArray(location)
            GL33.glVertexAttribDivisor(location, 1)
        }

        instanceNormalBuffer?.bind()
        for (i in 0 until 3) {
            val location = GL33.glGetAttribLocation(shader.id, "_he_InstanceNormal$i")
            if (location == -1) continue
            GL33.glVertexAttribPointer(location, 3, GL33.GL_FLOAT, false, NORMAL_STRIDE_BYTES, (i * 12).toLong())
            GL33.glEnableVertexAttribArray(location)
            GL33.glVertexAttribDivisor(location, 1)
        }

        bindIntegerAttribute(shader, "iris_UV1", instanceOverlayBuffer, 2, GL33.GL_INT, INT2_STRIDE_BYTES, 1)
        bindIntegerAttribute(shader, "iris_UV2", instanceLightBuffer, 2, GL33.GL_INT, INT2_STRIDE_BYTES, 1)
        bindIntegerAttribute(shader, "iris_Entity", instanceEntityBuffer, 3, GL33.GL_INT, ENTITY_INFO_STRIDE_BYTES, 1)
        bindIntegerAttribute(shader, "mc_Entity", instanceEntityBuffer, 3, GL33.GL_INT, ENTITY_INFO_STRIDE_BYTES, 1)

        indexBuffer?.bind()
        GL33.glBindVertexArray(0)

        return InstancedShaderBinding(runtimeVao, firstAttribLocation(shader.id, "Color", "iris_Color"))
    }

    private fun firstAttribLocation(program: Int, vararg names: String): Int {
        for (name in names) {
            val location = GL33.glGetAttribLocation(program, name)
            if (location != -1) return location
        }
        return -1
    }

    private fun bindVertexAttribute(
        shader: ShaderInstance,
        name: String,
        buffer: VboWrapper?,
        size: Int,
        type: Int,
        stride: Int = 0,
        offset: Long = 0L,
    ) {
        val location = GL33.glGetAttribLocation(shader.id, name)
        if (location == -1 || buffer == null) return

        buffer.bind()
        GL33.glVertexAttribPointer(location, size, type, false, stride, offset)
        GL33.glEnableVertexAttribArray(location)
    }

    private fun bindIntegerAsFloatAttribute(shader: ShaderInstance, name: String, buffer: VboWrapper?) {
        val location = GL33.glGetAttribLocation(shader.id, name)
        if (location == -1 || buffer == null) return

        buffer.bind()
        GL33.glVertexAttribPointer(location, 2, GL33.GL_INT, false, INT2_STRIDE_BYTES, 0L)
        GL33.glEnableVertexAttribArray(location)
        GL33.glVertexAttribDivisor(location, 1)
    }

    private fun bindIntegerAttribute(
        shader: ShaderInstance,
        name: String,
        buffer: VboWrapper?,
        size: Int,
        type: Int,
        stride: Int,
        divisor: Int = 0,
        offset: Long = 0L,
    ) {
        val location = GL33.glGetAttribLocation(shader.id, name)
        if (location == -1 || buffer == null) return

        buffer.bind()
        GL33.glVertexAttribIPointer(location, size, type, stride, offset)
        GL33.glEnableVertexAttribArray(location)
        if (divisor != 0) {
            GL33.glVertexAttribDivisor(location, divisor)
        }
    }

    override fun setupPipeline(
        pipeline: RenderPipeline,
        instance: MeshAttachment,
    ) {
        if (isDynamic && deformer != null) {
            pipeline.addSkinnable {
                if (instance.isVisible) {
                    deformer!!.compute(instance)
                }
            }
        }

        if (supportsInstancing) {
            pipeline.addInstancedRenderable {
                if (!instance.isVisible) return@addInstancedRenderable

                if (allowInstancing && InstanceBatchManager.canBatch()) {
                    InstanceBatchManager.submit(this@PipelineRenderer, captureInstance(instance))
                } else {
                    renderVAO(instance)
                }
            }
        } else {
            pipeline.addVAORenderable {
                if (!instance.isVisible) return@addVAORenderable
                renderVAO(instance)
            }
        }
    }

    private fun RenderContext.captureInstance(instance: MeshAttachment): SubmittedInstance {
        val nodeMatrix = instance.matrix.asMatrix4f()

        val modelView = Matrix4f(RenderSystem.getModelViewMatrix())
            .mul(stack.last().pose())
            .mul(nodeMatrix)

        val modelNormal = Matrix3f(stack.last().normal())
            .mul(normalMatrixOf(nodeMatrix))
        val modelViewNormal = normalMatrixOf(RenderSystem.getModelViewMatrix())
            .mul(modelNormal)

        return SubmittedInstance(
            modelView = modelView,
            modelNormal = modelNormal,
            modelViewNormal = modelViewNormal,
            overlay = overlay,
            light = instance.material.packedLight(light),
            sortKey = computeSortKey(modelView),
            material = instance.material,
            entityInfo = instancingEntityInfo
        )
    }

    private fun computeSortKey(modelView: Matrix4f): Float {
        val center = Vector3f(sortCenter.x, sortCenter.y, sortCenter.z)
        modelView.transformPosition(center)
        return center.lengthSquared()
    }

    fun shouldUseInstancing(instanceCount: Int, material: Material): Boolean {
        return supportsInstancing && primitive.prefersInstancing(
            instanceCount,
            material.blend == Material.Blend.BLEND,
        )
    }

    fun renderInstanced(
        instances: List<SubmittedInstance>,
        shader: ShaderInstance,
        layoutMode: InstancedShaderLayoutMode = InstancedShaderLayoutMode.FIXED,
    ) {
        if (instances.isEmpty()) return

        val material = instances.first().material
        val drawInstances = if (material.blend == Material.Blend.BLEND) {
            instances.sortedByDescending(SubmittedInstance::sortKey)
        } else {
            instances
        }
        updateInstanceBuffers(drawInstances, layoutMode)

        val binding = getInstancedBinding(shader, layoutMode)
        applyMaterial(shader, material, binding.colorLocation)

        RenderSystem.glBindVertexArray(binding.vao)

        indexBuffer?.bind()

        val count = primitive.indices?.size ?: (primitive.positionsCount / 3)
        if (indexBuffer != null) {
            GL33.glDrawElementsInstanced(GL33.GL_TRIANGLES, count, GL33.GL_UNSIGNED_INT, 0L, drawInstances.size)
        } else {
            GL33.glDrawArraysInstanced(GL33.GL_TRIANGLES, 0, count, drawInstances.size)
        }

        GL33.glBindVertexArray(0)
    }

    fun renderCapturedInstance(
        instance: SubmittedInstance,
        shader: ShaderInstance,
        layoutMode: InstancedShaderLayoutMode = InstancedShaderLayoutMode.FIXED,
    ) {
        applyMaterial(shader, instance.material)

        GL33.glVertexAttribI2i(3, instance.overlay and FFFF, instance.overlay shr 16 and FFFF)
        GL33.glVertexAttribI2i(4, instance.light and FFFF, instance.light shr 16 and FFFF)

        RenderSystem.glBindVertexArray(vao)

        indexBuffer?.bind()

        shader.MODEL_VIEW_MATRIX?.set(instance.modelView)
        shader.MODEL_VIEW_MATRIX?.upload()

        shader.getUniform("NormalMat")?.let {
            it.set(instance.normalFor(layoutMode))
            it.upload()
        }

        val count = primitive.indices?.size ?: (primitive.positionsCount / 3)
        if (indexBuffer != null) {
            GL33.glDrawElements(GL33.GL_TRIANGLES, count, GL33.GL_UNSIGNED_INT, 0L)
        } else {
            GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, count)
        }

        GL33.glBindVertexArray(0)
    }
    private fun updateInstanceBuffers(
        instances: List<SubmittedInstance>,
        layoutMode: InstancedShaderLayoutMode,
    ) {
        ensureInstanceCapacity(instances.size)

        val modelViews = BufferUtils.createFloatBuffer(instances.size * 16)
        val normals = BufferUtils.createFloatBuffer(instances.size * 9)
        val overlays = BufferUtils.createIntBuffer(instances.size * 2)
        val lights = BufferUtils.createIntBuffer(instances.size * 2)
        val entities = BufferUtils.createIntBuffer(instances.size * 3)

        for (instance in instances) {
            putModelView(modelViews, instance.modelView)
            putNormal(normals, instance.normalFor(layoutMode))
            overlays.put(instance.overlay and FFFF).put(instance.overlay shr 16 and FFFF)
            lights.put(instance.light and FFFF).put(instance.light shr 16 and FFFF)
            entities.put(instance.entityInfo.entity).put(instance.entityInfo.blockEntity).put(instance.entityInfo.item)
        }

        modelViews.flip()
        normals.flip()
        overlays.flip()
        lights.flip()
        entities.flip()

        instanceModelViewBuffer?.bind()
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0, modelViews)

        instanceNormalBuffer?.bind()
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0, normals)

        instanceOverlayBuffer?.bind()
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0, overlays)

        instanceLightBuffer?.bind()
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0, lights)

        instanceEntityBuffer?.bind()
        GL33.glBufferSubData(GL33.GL_ARRAY_BUFFER, 0, entities)
    }

    private fun ensureInstanceCapacity(instanceCount: Int) {
        if (instanceCount <= instanceCapacity) return

        var capacity = instanceCapacity.coerceAtLeast(1)
        while (capacity < instanceCount) {
            capacity *= 2
        }

        instanceModelViewBuffer?.apply {
            bind()
            GL33.glBufferData(GL33.GL_ARRAY_BUFFER, capacity * MODEL_VIEW_STRIDE_BYTES.toLong(), GL33.GL_DYNAMIC_DRAW)
        }
        instanceNormalBuffer?.apply {
            bind()
            GL33.glBufferData(GL33.GL_ARRAY_BUFFER, capacity * NORMAL_STRIDE_BYTES.toLong(), GL33.GL_DYNAMIC_DRAW)
        }
        instanceOverlayBuffer?.apply {
            bind()
            GL33.glBufferData(GL33.GL_ARRAY_BUFFER, capacity * INT2_STRIDE_BYTES.toLong(), GL33.GL_DYNAMIC_DRAW)
        }
        instanceLightBuffer?.apply {
            bind()
            GL33.glBufferData(GL33.GL_ARRAY_BUFFER, capacity * INT2_STRIDE_BYTES.toLong(), GL33.GL_DYNAMIC_DRAW)
        }
        instanceEntityBuffer?.apply {
            bind()
            GL33.glBufferData(GL33.GL_ARRAY_BUFFER, capacity * ENTITY_INFO_STRIDE_BYTES.toLong(), GL33.GL_DYNAMIC_DRAW)
        }

        instanceCapacity = capacity
    }

    private fun putModelView(buffer: java.nio.FloatBuffer, matrix: Matrix4f) {
        buffer.put(matrix.m00()).put(matrix.m01()).put(matrix.m02()).put(matrix.m03())
        buffer.put(matrix.m10()).put(matrix.m11()).put(matrix.m12()).put(matrix.m13())
        buffer.put(matrix.m20()).put(matrix.m21()).put(matrix.m22()).put(matrix.m23())
        buffer.put(matrix.m30()).put(matrix.m31()).put(matrix.m32()).put(matrix.m33())
    }

    private fun putNormal(buffer: java.nio.FloatBuffer, matrix: Matrix3f) {
        buffer.put(matrix.m00()).put(matrix.m01()).put(matrix.m02())
        buffer.put(matrix.m10()).put(matrix.m11()).put(matrix.m12())
        buffer.put(matrix.m20()).put(matrix.m21()).put(matrix.m22())
    }

    private fun RenderContext.renderVAO(instance: MeshAttachment) {
        val shader = RenderSystem.getShader() ?: return
        val nodeMatrix = instance.matrix.asMatrix4f()

        applyMaterial(shader, instance.material)

        val packedLight = instance.material.packedLight(light)
        GL33.glVertexAttribI2i(4, packedLight and FFFF, packedLight shr 16 and FFFF)

        RenderSystem.glBindVertexArray(vao)

        indexBuffer?.bind()

        val modelView = Matrix4f(RenderSystem.getModelViewMatrix()).mul(stack.last().pose())
        modelView.mul(nodeMatrix)
        shader.MODEL_VIEW_MATRIX?.set(modelView)
        shader.MODEL_VIEW_MATRIX?.upload()

        shader.getUniform("NormalMat")?.let {
            val modelNormal = Matrix3f(stack.last().normal())
                .mul(normalMatrixOf(nodeMatrix))
            val normal = if (areShadersEnabled) {
                normalMatrixOf(RenderSystem.getModelViewMatrix()).mul(modelNormal)
            } else {
                modelNormal
            }
            it.set(normal)
            it.upload()
        }

        val count = primitive.indices?.size ?: (primitive.positionsCount / 3)
        if (indexBuffer != null) {
            GL33.glDrawElements(GL33.GL_TRIANGLES, count, GL33.GL_UNSIGNED_INT, 0L)
        } else {
            GL33.glDrawArrays(GL33.GL_TRIANGLES, 0, count)
        }

        GL33.glBindVertexArray(0)
    }

    private fun applyMaterial(shader: ShaderInstance, material: Material, colorLocation: Int = 1) {
        if (colorLocation != -1) {
            GL33.glVertexAttrib4f(colorLocation, material.color.r, material.color.g, material.color.b, material.color.a)
        }

        var normal = 0
        var specular = 0

        if (areShadersEnabled) {
            GL33.glGetUniformLocation(shader.id, "normals").takeIf { it != -1 }?.let {
                RenderSystem.activeTexture(COLOR_MAP_INDEX + GL33.glGetUniformi(shader.id, it))
                normal = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
                RenderSystem.bindTexture(material.normalTexture.toTexture().id)
            }
            GL33.glGetUniformLocation(shader.id, "specular").takeIf { it != -1 }?.let {
                RenderSystem.activeTexture(COLOR_MAP_INDEX + GL33.glGetUniformi(shader.id, it))
                specular = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
                RenderSystem.bindTexture(material.specularTexture.toTexture().id)
            }
        }

        RenderSystem.activeTexture(COLOR_MAP_INDEX)
        RenderSystem.bindTexture(Minecraft.getInstance().textureManager.getTexture(material.texture).id)

        if (material.doubleSided) RenderSystem.disableCull()
        else RenderSystem.enableCull()

        when (material.blend) {
            Material.Blend.OPAQUE -> RenderSystem.disableBlend()
            Material.Blend.BLEND -> {
                RenderSystem.enableBlend()
                RenderSystem.defaultBlendFunc()
            }
        }
    }

    override fun destroy() {
        GL33.glDeleteVertexArrays(vao)
        if (instancedVao != -1) GL33.glDeleteVertexArrays(instancedVao)
        clearRuntimeInstancedBindings()
        posBuffer?.delete()
        norBuffer?.delete()
        tanBuffer?.delete()
        uvBuffer?.delete()
        midBuffer?.delete()
        indexBuffer?.delete()
        instanceModelViewBuffer?.delete()
        instanceNormalBuffer?.delete()
        instanceOverlayBuffer?.delete()
        instanceLightBuffer?.delete()
        instanceEntityBuffer?.delete()

        deformer?.destroy()
        unregisterRenderer(this)
    }

    private fun clearRuntimeInstancedBindings() {
        runtimeInstancedBindings.values.forEach { GL33.glDeleteVertexArrays(it.vao) }
        runtimeInstancedBindings.clear()
    }

    companion object {
        private const val MODEL_VIEW_STRIDE_BYTES = 16 * Float.SIZE_BYTES
        private const val NORMAL_STRIDE_BYTES = 9 * Float.SIZE_BYTES
        private const val INT2_STRIDE_BYTES = 2 * Int.SIZE_BYTES
        private const val ENTITY_INFO_STRIDE_BYTES = 3 * Int.SIZE_BYTES
        private const val FFFF = '\uffff'.code
        private val RENDERERS = Collections.newSetFromMap(WeakHashMap<PipelineRenderer, Boolean>())

        fun invalidateRuntimeInstancedBindings() {
            RENDERERS.toList().forEach(PipelineRenderer::clearRuntimeInstancedBindings)
        }

        private fun registerRenderer(renderer: PipelineRenderer) {
            RENDERERS += renderer
        }

        private fun unregisterRenderer(renderer: PipelineRenderer) {
            RENDERERS -= renderer
        }
    }

    private data class InstancedShaderBinding(val vao: Int, val colorLocation: Int)
}

private fun normalMatrixOf(matrix: Matrix4f): Matrix3f {
    val linear = Matrix3f(matrix)
    val determinant = linear.determinant()

    if (!determinant.isFinite() || abs(determinant) < 1e-8f) {
        return Matrix3f()
    }

    val result = linear
        .invert()
        .transpose()

    val uniformCompensation = cbrt(abs(determinant))

    result.scale(uniformCompensation)
    return result
}
