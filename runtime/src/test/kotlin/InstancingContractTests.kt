import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Vector3f
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.InstancedShaderLayoutMode
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.SlotTransform
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.composeSlotTransform
import kotlin.test.assertEquals

class InstancingContractTests {
    private val modelView = Matrix4f().translation(0f, 0f, -5f)
    private val modelNormal = Matrix3f().rotationY(Math.toRadians(90.0).toFloat())
    private val viewNormal = Matrix3f().rotationX(Math.toRadians(90.0).toFloat())
    private val slots = FloatArray(32).also {
        Matrix4f().get(it, 0)
        Matrix4f().translation(1f, 2f, 3f).scale(2f).get(it, 16)
    }

    @Test
    fun `slot sits under the instance model view`() {
        val transform = composeSlotTransform(modelView, modelNormal, viewNormal, slots, 1, InstancedShaderLayoutMode.FIXED, SlotTransform())
        val origin = transform.modelView.transformPosition(Vector3f())
        assertClose(Vector3f(1f, 2f, -2f), origin)
    }

    @Test
    fun `vanilla and shader pack layouts get normals in their own spaces`() {
        val axis = Vector3f(1f, 0f, 0f)
        val fixed = composeSlotTransform(modelView, modelNormal, viewNormal, slots, 1, InstancedShaderLayoutMode.FIXED, SlotTransform())
        val runtime = composeSlotTransform(modelView, modelNormal, viewNormal, slots, 1, InstancedShaderLayoutMode.RUNTIME, SlotTransform())

        assertClose(modelNormal.transform(Vector3f(axis)), fixed.normal.transform(Vector3f(axis)))
        assertClose(viewNormal.transform(modelNormal.transform(Vector3f(axis))), runtime.normal.transform(Vector3f(axis)))
    }

    private fun assertClose(expected: Vector3f, actual: Vector3f) {
        assertEquals(expected.x, actual.x, 1e-5f)
        assertEquals(expected.y, actual.y, 1e-5f)
        assertEquals(expected.z, actual.z, 1e-5f)
    }
}
