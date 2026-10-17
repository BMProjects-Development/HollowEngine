package ru.hollowhorizon.hollowengine.client.render

import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.item.ItemStack
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import org.lwjgl.opengl.GL11
import ru.hollowhorizon.hollowengine.client.utils.color
import ru.hollowhorizon.hollowengine.client.utils.vertex
import ru.hollowhorizon.hollowengine.common.utils.Color
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.min

object OpenGLUtils {

    fun drawLine(
        bufferbuilder: BufferBuilder, matrix: Matrix4f,
        from: Vector3d, to: Vector3d,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        bufferbuilder.vertex(matrix, from.x.toFloat(), from.y.toFloat() - 0.1f, from.z.toFloat())
            .color(r, g, b, a)
        bufferbuilder.vertex(matrix, to.x.toFloat(), to.y.toFloat() - 0.1f, to.z.toFloat()).color(r, g, b, a)
    }

    fun renderBoundingBox(stack: PoseStack, min: Vec3f, max: Vec3f, color: Color) {
        RenderSystem.enableBlend()
        RenderSystem.defaultBlendFunc()
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        RenderSystem.lineWidth(1.0f)

        val tessellator = Tesselator.getInstance()
        val buffer = tessellator.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR)


        val matrix = stack.last().pose()
        val (r, g, b, a) = color

        fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float) {
            buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a)
            buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a)
        }

        line(min.x, min.y, min.z, max.x, min.y, min.z)
        line(min.x, min.y, min.z, min.x, max.y, min.z)
        line(min.x, min.y, min.z, min.x, min.y, max.z)

        line(max.x, max.y, max.z, min.x, max.y, max.z)
        line(max.x, max.y, max.z, max.x, min.y, max.z)
        line(max.x, max.y, max.z, max.x, max.y, min.z)

        line(min.x, max.y, min.z, max.x, max.y, min.z)
        line(min.x, max.y, min.z, min.x, max.y, max.z)

        line(max.x, min.y, min.z, max.x, max.y, min.z)
        line(max.x, min.y, min.z, max.x, min.y, max.z)

        line(min.x, min.y, max.z, max.x, min.y, max.z)
        line(min.x, min.y, max.z, min.x, max.y, max.z)

        BufferUploader.drawWithShader(buffer.buildOrThrow())

        RenderSystem.disableBlend()
    }
}

operator fun Color.component1() = r
operator fun Color.component2() = g
operator fun Color.component3() = b
operator fun Color.component4() = a

val CUSTOM_IMGUI_LIGHT_0: Vector3f = Vector3f(-0.3f, 1f, 1f).normalize()
val CUSTOM_IMGUI_LIGHT_1: Vector3f = Vector3f(0.3f, -1f, -1f).normalize()

fun ItemStack.render(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    scale: Float = 1f,
    rotation: Float = 0f,
    stack: PoseStack = PoseStack(),
) {
    val xOffset = x + width / 2
    val yOffset = y + height / 2
    stack.translate(xOffset, yOffset, 0f)

    stack.mulPose(Matrix4f().scaling(1f, -1f, 1f))

    val newScale = min(width, height) * 0.95f * scale
    stack.scale(newScale, newScale, newScale)
    stack.mulPose(Quaternionf().rotateZ(rotation * Mth.DEG_TO_RAD))


    val src = Minecraft.getInstance().renderBuffers().bufferSource()
    val model = Minecraft.getInstance().itemRenderer.getModel(this, Minecraft.getInstance().level, null, 0)

    val flat = !model.usesBlockLight()
    val depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
    val depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)

    if (flat) {
        Lighting.setupForFlatItems()
    } else {
        Lighting.setupFor3DItems()
    }
    RenderSystem.enableDepthTest()
    GL11.glDepthMask(true)
    try {
        Minecraft.getInstance().itemRenderer.render(
            this,
            ItemDisplayContext.GUI,
            false,
            stack, src, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, model
        )
    } finally {
        src.endBatch()
        Lighting.setupFor3DItems()
        if (!depthEnabled) RenderSystem.disableDepthTest()
        GL11.glDepthMask(depthMask)
    }
}

fun fill(stack: PoseStack, renderType: RenderType, minX: Int, minY: Int, maxX: Int, maxY: Int, z: Int, color: Int) {
    var minX = minX
    var minY = minY
    var maxX = maxX
    var maxY = maxY
    var i: Int
    val matrix4f: Matrix4f = stack.last().pose()
    if (minX < maxX) {
        i = minX
        minX = maxX
        maxX = i
    }
    if (minY < maxY) {
        i = minY
        minY = maxY
        maxY = i
    }
    val src = Minecraft.getInstance().renderBuffers().bufferSource()
    val vertexConsumer: VertexConsumer = src.getBuffer(renderType)

    vertexConsumer.vertex(matrix4f, minX.toFloat(), minY.toFloat(), z.toFloat()).color(color)
    vertexConsumer.vertex(matrix4f, minX.toFloat(), maxY.toFloat(), z.toFloat()).color(color)
    vertexConsumer.vertex(matrix4f, maxX.toFloat(), maxY.toFloat(), z.toFloat()).color(color)
    vertexConsumer.vertex(matrix4f, maxX.toFloat(), minY.toFloat(), z.toFloat()).color(color)
}

fun fill(
    stack: PoseStack,
    vertexConsumer: VertexConsumer,
    minX: Int,
    minY: Int,
    maxX: Int,
    maxY: Int,
    z: Int,
    color: Int,
) {
    var minX = minX
    var minY = minY
    var maxX = maxX
    var maxY = maxY
    var i: Int
    val matrix4f: Matrix4f = stack.last().pose()
    if (minX < maxX) {
        i = minX
        minX = maxX
        maxX = i
    }
    if (minY < maxY) {
        i = minY
        minY = maxY
        maxY = i
    }
    vertexConsumer.vertex(matrix4f, minX.toFloat(), minY.toFloat(), z.toFloat()).color(color)
    vertexConsumer.vertex(matrix4f, minX.toFloat(), maxY.toFloat(), z.toFloat()).color(color)
    vertexConsumer.vertex(matrix4f, maxX.toFloat(), maxY.toFloat(), z.toFloat()).color(color)
    vertexConsumer.vertex(matrix4f, maxX.toFloat(), minY.toFloat(), z.toFloat()).color(color)
}