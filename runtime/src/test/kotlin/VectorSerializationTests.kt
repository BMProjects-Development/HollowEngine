import io.netty.buffer.Unpooled
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import net.minecraft.network.FriendlyByteBuf
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.attachments.editor.ObjectPlacement
import ru.hollowhorizon.hollowengine.common.utils.bytebuf.FriendlyByteBufDecoder
import ru.hollowhorizon.hollowengine.common.utils.bytebuf.FriendlyByteBufEncoder
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3d
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.VfxBoneAttachmentSpec
import kotlin.test.assertEquals

/**
 * Vectors are written as collections. The packet buffer reads them sequentially and needs their size read
 * back, or every field after the first vector comes out of the wrong bytes and the packet fails to decode.
 */
class VectorSerializationTests {
    @Test
    fun `vectors survive the packet buffer together with the fields after them`() {
        val placement = ObjectPlacement(Vec3d(1.5, -64.0, 1.0e6), Vec3f(10f, 20f, 30f), Vec3f(0.5f, 2f, 1f))
        assertEquals(placement, throughBuffer(ObjectPlacement.serializer(), placement))

        val effect = VfxBoneAttachmentSpec("fire", "hollowengine:vfx/fire.vfx", Vec3f(0f, 1.25f, -0.5f), Vec3f(90f, 0f, 0f), scale = 2f)
        assertEquals(effect, throughBuffer(VfxBoneAttachmentSpec.serializer(), effect))
    }

    @Test
    fun `vectors still read from json`() {
        val placement = ObjectPlacement(Vec3d(3.0, 4.0, 5.0), Vec3f(0f, 90f, 0f), Vec3f(1f, 1f, 2f))
        assertEquals(placement, Json.decodeFromString(ObjectPlacement.serializer(), Json.encodeToString(ObjectPlacement.serializer(), placement)))
    }

    private fun <T> throughBuffer(serializer: KSerializer<T>, value: T): T {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        FriendlyByteBufEncoder(EmptySerializersModule(), buffer).encodeSerializableValue(serializer, value)
        val decoded = FriendlyByteBufDecoder(EmptySerializersModule(), buffer).decodeSerializableValue(serializer)
        assertEquals(0, buffer.readableBytes())
        return decoded
    }
}
