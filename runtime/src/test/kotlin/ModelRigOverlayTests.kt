import net.minecraft.nbt.Tag
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.models.ItemSlotAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.models.RigBone
import ru.hollowhorizon.hollowengine.common.models.RigPose
import ru.hollowhorizon.hollowengine.common.models.RigItemSlot
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.serialization.deserialize
import ru.hollowhorizon.hollowengine.common.utils.serialization.serialize
import ru.hollowhorizon.hollowengine.common.utils.bytebuf.ByteBufFormat
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.VfxBoneAttachmentSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect
import ru.hollowhorizon.hollowengine.common.vfx.VfxEmitterSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxPlaneSpec
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxNoiseSpec
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What an entity hangs on its model is laid over the model's rig file: by bone name and attachment id, the
 * entity's winning, without dropping anything the file has that the entity does not touch.
 */
class ModelRigOverlayTests {
    private val asset = ModelRig(
        bones = mapOf(
            "Hand" to RigBone(alias = "right_hand", attachments = listOf(ItemSlotAttachmentSpec("item"), VfxBoneAttachmentSpec("glow", effect = "a:vfx/glow.vfx"))),
            "Head" to RigBone(hidden = true),
        ),
        attachments = listOf(VfxBoneAttachmentSpec("aura", effect = "a:vfx/aura.vfx")),
    )

    @Test
    fun `an entity that hangs nothing sees the rig file as it is`() {
        assertSame(asset, asset.overlay(ModelRig.EMPTY))
    }

    @Test
    fun `an attachment with a taken id replaces the file's in its place, a new one goes last`() {
        val instance = ModelRig(
            bones = mapOf(
                "Hand" to RigBone(attachments = listOf(VfxBoneAttachmentSpec("glow", effect = "b:vfx/fire.vfx"), ModelAttachmentSpec("sword", model = "b:models/sword.glb"))),
            ),
        )
        val hand = asset.overlay(instance).bone("Hand")!!
        assertEquals(listOf("item", "glow", "sword"), hand.attachments.map { it.id })
        assertEquals("b:vfx/fire.vfx", (hand.attachments[1] as VfxBoneAttachmentSpec).effect)
        assertEquals(RigItemSlot.MAINHAND, (hand.attachments[0] as ItemSlotAttachmentSpec).slot)
        assertEquals("right_hand", hand.alias)
    }

    @Test
    fun `bones and model attachments the entity adds join those of the file`() {
        val instance = ModelRig(
            bones = mapOf("Tail" to RigBone(hidden = true), "Head" to RigBone(alias = "skull")),
            attachments = listOf(ModelAttachmentSpec("lamp", model = "b:models/lamp.glb")),
        )
        val merged = asset.overlay(instance)
        assertEquals(setOf("Hand", "Head", "Tail"), merged.bones.keys)
        assertEquals(listOf("aura", "lamp"), merged.attachments.map { it.id })
        assertTrue(merged.bone("Head")!!.hidden, "a bone hidden by the file stays hidden")
        assertEquals("skull", merged.bone("Head")!!.alias)
    }

    @Test
    fun `a posed bone and an entity's own effect come back from storage and the network equal`() {
        val emitter = VfxEmitterSpec(id = "sparks", modules = listOf(VfxNoiseSpec()), children = listOf(VfxPlaneSpec(id = "quad")))
        val effect = VfxEffect(nodes = listOf(emitter))
        val rig = ModelRig(
            bones = mapOf("Head" to RigBone(pose = RigPose(Vec3f(0f, 1f, 0f), QuatF(0f, 0.38268343f, 0f, 0.9238795f), Vec3f(1f, 2f, 1f)))),
            attachments = listOf(VfxBoneAttachmentSpec("aura", effect = "a:vfx/aura.vfx", ownEffect = effect)),
        )

        val stored = NBTFormat.deserialize<ModelRig, Tag>(NBTFormat.serialize(rig))
        val sent = ByteBufFormat.deserialize(ModelRig.serializer(), ByteBufFormat.serialize(ModelRig.serializer(), rig))

        assertEquals(rig, stored, "an edit coming back from the server is told from a foreign one by equality")
        assertEquals(rig, sent)
    }

    @Test
    fun `what attachments take in place leaves the structure as it was`() {
        val moved = ModelRig(
            bones = mapOf(
                "Hand" to RigBone(
                    attachments = listOf(
                        VfxBoneAttachmentSpec("glow", effect = "a:vfx/other.vfx", offset = Vec3f(1f, 0f, 0f), ownEffect = VfxEffect()),
                        ModelAttachmentSpec("sword", model = "b:models/sword.glb", scale = 2f),
                    ),
                ),
            ),
        )
        val placed = ModelRig(
            bones = mapOf(
                "Hand" to RigBone(
                    attachments = listOf(VfxBoneAttachmentSpec("glow"), ModelAttachmentSpec("sword", model = "b:models/sword.glb")),
                ),
            ),
        )
        assertEquals(placed.structure(), moved.structure())

        val swapped = placed.withBone("Hand", RigBone(attachments = listOf(ModelAttachmentSpec("sword", model = "b:models/axe.glb"))))
        assertNotEquals(placed.structure(), swapped.structure(), "another nested model has to be built")
    }
}
