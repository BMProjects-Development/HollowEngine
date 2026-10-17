package ru.hollowhorizon.hollowengine.client.ui.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.*
import net.minecraft.client.renderer.GameRenderer
import ru.hollowhorizon.hollowengine.client.ui.UiColor
import ru.hollowhorizon.hollowengine.client.ui.UiMatrix4

private const val FloatsPerVertex = 7
private const val VerticesPerTriangle = 3
private const val FloatsPerTriangle = FloatsPerVertex * VerticesPerTriangle

/** Reusable primitive vertex storage. A frame only grows the backing array; clearing allocates nothing. */
internal class UiTriangleBatch(initialTriangleCapacity: Int = 128) {
    private var vertices = FloatArray(initialTriangleCapacity * FloatsPerTriangle)
    private var size = 0

    val isEmpty: Boolean get() = size == 0

    fun clear() {
        size = 0
    }

    fun addTriangle(
        transform: UiMatrix4,
        firstX: Float,
        firstY: Float,
        firstColor: UiColor,
        secondX: Float,
        secondY: Float,
        secondColor: UiColor,
        thirdX: Float,
        thirdY: Float,
        thirdColor: UiColor,
    ) {
        ensureCapacity(size + FloatsPerTriangle)
        appendVertex(transform, firstX, firstY, firstColor)
        appendVertex(transform, secondX, secondY, secondColor)
        appendVertex(transform, thirdX, thirdY, thirdColor)
    }

    fun writeTo(consumer: VertexConsumer) {
        var offset = 0
        while (offset < size) {
            consumer.addVertex(vertices[offset], vertices[offset + 1], vertices[offset + 2])
                .setColor(vertices[offset + 3], vertices[offset + 4], vertices[offset + 5], vertices[offset + 6])
            offset += FloatsPerVertex
        }
    }

    private fun appendVertex(transform: UiMatrix4, x: Float, y: Float, color: UiColor) {
        transform.transform(x, y, 0f, vertices, size)
        vertices[size + 3] = color.red
        vertices[size + 4] = color.green
        vertices[size + 5] = color.blue
        vertices[size + 6] = color.alpha
        size += FloatsPerVertex
    }

    private fun ensureCapacity(required: Int) {
        if (required <= vertices.size) return
        vertices = vertices.copyOf(maxOf(required, vertices.size * 2))
    }
}

internal fun drawBatchedTriangles(batch: UiTriangleBatch) {
    if (batch.isEmpty) return
    withCullStatePreserved {
        RenderSystem.disableCull()
        RenderSystem.enableBlend()
        configureUiBlend()
        RenderSystem.setShader(GameRenderer::getPositionColorShader)
        val buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR)
        batch.writeTo(buffer)
        BufferUploader.drawWithShader(buffer.buildOrThrow())
    }
}
