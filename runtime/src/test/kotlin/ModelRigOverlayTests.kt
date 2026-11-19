import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.decals.Decal
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.utils.bytebuf.ByteBufFormat
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.serialization.deserialize
import ru.hollowhorizon.hollowengine.common.utils.serialization.serialize
import ru.hollowhorizon.hollowengine.common.vfx.VfxComponent
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect
import ru.hollowhorizon.hollowengine.common.vfx.VfxEmitterSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxPlaneSpec
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxNoiseSpec
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What an entity hangs on its model is laid over the model's rig file: by bone name and attachment id, the
 * entity's winning, without dropping anything the file has that the entity does not touch.
 */
class ModelRigOverlayTests {
    @Test
    fun `a wrapped decal from a legacy rig loads as component data on its placed node`() {
        val raw = CompoundTag().apply {
            putString("id", "sticker")
            put("decal", CompoundTag().apply {
                putString("texture", "test:sticker.png")
                putFloat("width", 2.5f)
            })
        }
        val entry = CompoundTag().apply { put(Decal.TYPE_ID, raw) }
        val stored = CompoundTag().apply { put("attachments", ListTag().apply { add(entry) }) }

        val rig = NBTFormat.deserialize<ModelRig, Tag>(stored)
        val (bone, component) = rig.allAttachments().single()
        assertEquals(Decal(id = "sticker", texture = "test:sticker.png", width = 2.5f), component)
        assertEquals(RigBoneOrigin(), rig.bone(bone!!)?.origin)
        assertEquals(rig, NBTFormat.deserialize<ModelRig, Tag>(NBTFormat.serialize(rig)))
    }

    private val asset = ModelRig(
        bones = mapOf(
            "Hand" to RigBone(alias = "right_hand", attachments = listOf(ItemSlotAttachmentSpec("item"), VfxComponent("glow", effect = "a:vfx/glow.vfx"))),
            "Head" to RigBone(hidden = true),
        ),
        attachments = listOf(VfxComponent("aura", effect = "a:vfx/aura.vfx")),
    )

    @Test
    fun `an entity that hangs nothing sees the rig file as it is`() {
        assertSame(asset, asset.overlay(ModelRig.EMPTY))
    }

    @Test
    fun `an attachment with a taken id replaces the file's in its place, a new one goes last`() {
        val instance = ModelRig(
            bones = mapOf(
                "Hand" to RigBone(attachments = listOf(VfxComponent("glow", effect = "b:vfx/fire.vfx"), ModelAttachmentSpec("sword", model = "b:models/sword.glb"))),
            ),
        )
        val hand = asset.overlay(instance).bone("Hand")!!
        assertEquals(listOf("item", "glow", "sword"), hand.attachments.map { it.rigId })
        assertEquals("b:vfx/fire.vfx", (hand.attachments[1] as VfxComponent).effect)
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
        assertEquals(listOf("aura", "lamp"), merged.attachments.map { it.rigId })
        assertTrue(merged.bone("Head")!!.hidden, "a bone hidden by the file stays hidden")
        assertEquals("skull", merged.bone("Head")!!.alias)
    }

    @Test
    fun `a posed bone and an entity's own effect come back from storage and the network equal`() {
        val emitter = VfxEmitterSpec(id = "sparks", modules = listOf(VfxNoiseSpec()), children = listOf(VfxPlaneSpec(id = "quad")))
        val effect = VfxEffect(nodes = listOf(emitter))
        val rig = ModelRig(
            bones = mapOf("Head" to RigBone(pose = RigPose(Vec3f(0f, 1f, 0f), QuatF(0f, 0.38268343f, 0f, 0.9238795f), Vec3f(1f, 2f, 1f)))),
            attachments = listOf(VfxComponent("aura", effect = "a:vfx/aura.vfx", ownEffect = effect)),
        )

        val stored = NBTFormat.deserialize<ModelRig, Tag>(NBTFormat.serialize(rig))
        val sent = ByteBufFormat.deserialize(ModelRig.serializer(), ByteBufFormat.serialize(ModelRig.serializer(), rig))

        assertEquals(rig, stored, "an edit coming back from the server is told from a foreign one by equality")
        assertEquals(rig, sent)
    }

    @Test
    fun `an unavailable legacy rig component preserves its name and data`() {
        val payload = CompoundTag().apply {
            putString("id", "constraint")
            putFloat("weight", 0.5f)
        }
        val entry = CompoundTag().apply { put("test:rig/missing", payload) }
        val stored = CompoundTag().apply { put("attachments", ListTag().apply { add(entry) }) }

        val rig = NBTFormat.deserialize<ModelRig, Tag>(stored)
        val unknown = assertIs<UnknownRigComponent>(rig.holder(null).attachment("constraint"))
        assertEquals(payload, unknown.payload)
        val written = assertIs<CompoundTag>(NBTFormat.serialize(rig))
        assertEquals(entry, written.getList("attachments", Tag.TAG_COMPOUND.toInt()).single())
    }

    @Test
    fun `a legacy placement is saved as a child bone and migrated only once`() {
        val position = Vec3f(1f, 2f, 3f)
        val placement = LegacyNodePlacement(offset = position, rotation = Vec3f(0f, 45f, 0f), scale = 2f)
        val spec = (NBTFormat.serialize(LegacyNodePlacement.serializer(), placement) as CompoundTag).apply {
            putString("id", "glow")
            putString("effect", "test:glow")
        }
        val entry = CompoundTag().apply { put("hollowengine:rig/vfx", spec) }
        val hand = CompoundTag().apply { put("attachments", ListTag().apply { add(entry) }) }
        val old = CompoundTag().apply { put("bones", CompoundTag().apply { put("hand", hand) }) }

        val rig = NBTFormat.deserialize<ModelRig, Tag>(old)
        val child = rig.bones.values.single { it.origin != null }
        assertEquals(placement.origin("hand"), child.origin)
        assertEquals(VfxComponent(id = "glow", effect = "test:glow"), child.attachments.single())
        assertTrue(rig.holder("hand").attachments.isEmpty())
        assertEquals(rig, NBTFormat.deserialize<ModelRig, Tag>(NBTFormat.serialize(rig)))
    }
}
