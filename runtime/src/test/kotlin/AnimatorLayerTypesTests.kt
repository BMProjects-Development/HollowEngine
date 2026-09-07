import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.nbt.Tag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.common.models.AnimationExpression
import ru.hollowhorizon.hollowengine.common.models.Animator
import ru.hollowhorizon.hollowengine.common.models.AnimatorLayerSpec
import ru.hollowhorizon.hollowengine.common.models.AnimatorLayerType
import ru.hollowhorizon.hollowengine.common.models.AnimatorLayerTypes
import ru.hollowhorizon.hollowengine.common.models.BoneMask
import ru.hollowhorizon.hollowengine.common.models.ClipAnimationLayerSpec
import ru.hollowhorizon.hollowengine.common.models.LayerBlendMode
import ru.hollowhorizon.hollowengine.common.models.UnknownAnimatorLayerSpec
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.serialization.deserialize
import ru.hollowhorizon.hollowengine.common.utils.serialization.serialize
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * An animator layer an addon could bring: the engine knows nothing about it beyond the extension point.
 */
@Serializable
@SerialName(AddonLayerId)
private data class AddonLayerSpec(
    override val id: String = "addon-layer",
    val stiffness: Float = 4.5f,
    override val weight: AnimationExpression = AnimationExpression.ONE,
    override val priority: Int = 0,
    override val blendMode: LayerBlendMode = LayerBlendMode.Override,
    override val mask: BoneMask = BoneMask.full(),
    override val fadeIn: Float = 0f,
    override val fadeOut: Float = 0f,
) : AnimatorLayerSpec() {
    override fun withCommon(
        id: String,
        weight: AnimationExpression,
        priority: Int,
        blendMode: LayerBlendMode,
        mask: BoneMask,
        fadeIn: Float,
        fadeOut: Float,
    ) = copy(
        id = id, weight = weight, priority = priority, blendMode = blendMode,
        mask = mask, fadeIn = fadeIn, fadeOut = fadeOut,
    )
}

private const val AddonLayerId = "test:animator/addon"

class AnimatorLayerTypesTests {
    private var registration: ExtensionHandle? = null

    private val animator = Animator(
        layers = listOf(
            ClipAnimationLayerSpec(id = "clip", animation = "wave"),
            AddonLayerSpec(id = "addon-layer", stiffness = 7.25f, weight = AnimationExpression("data.ragdoll")),
        )
    )

    @AfterEach
    fun cleanup() {
        registration?.close()
        registration = null
    }

    private fun registerAddonType() {
        registration = AnimatorLayerTypes.register(
            AnimatorLayerType(
                id = AddonLayerId,
                specClass = AddonLayerSpec::class,
                serializer = AddonLayerSpec.serializer(),
                titleKey = "test.addon_layer",
            )
        )
    }

    @Test
    fun `a registered layer type round-trips`() {
        registerAddonType()

        val restored = NBTFormat.deserialize<Animator, Tag>(NBTFormat.serialize(animator))

        assertEquals(animator, restored)
    }

    @Test
    fun `a layer whose type is gone is kept as it was stored`() {
        registerAddonType()
        val stored = NBTFormat.serialize<Animator, Tag>(animator)
        registration?.close()
        registration = null

        val withoutAddon = NBTFormat.deserialize<Animator, Tag>(stored)
        val unknown = assertIs<UnknownAnimatorLayerSpec>(withoutAddon.layers[1])
        assertEquals(AddonLayerId, unknown.typeId)
        assertEquals("addon-layer", unknown.id)
        assertEquals("data.ragdoll", unknown.weight.source)
        assertEquals(7.25f, unknown.payload.getFloat("stiffness"))

        assertEquals(stored, NBTFormat.serialize<Animator, Tag>(withoutAddon))

        registerAddonType()
        assertEquals(animator, NBTFormat.deserialize<Animator, Tag>(stored))
    }

    @Test
    fun `editing the common part of an unknown layer keeps the rest of it`() {
        registerAddonType()
        val stored = NBTFormat.serialize<Animator, Tag>(animator)
        registration?.close()
        registration = null

        val unknown = assertIs<UnknownAnimatorLayerSpec>(
            NBTFormat.deserialize<Animator, Tag>(stored).layers[1]
        )
        val renamed = assertIs<UnknownAnimatorLayerSpec>(unknown.withCommon(id = "renamed", priority = 3))

        assertEquals("renamed", renamed.id)
        assertEquals(3, renamed.priority)
        assertEquals(7.25f, renamed.payload.getFloat("stiffness"))
        assertEquals("data.ragdoll", renamed.weight.source)
    }
}
