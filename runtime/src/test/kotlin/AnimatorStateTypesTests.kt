import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.nbt.Tag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.common.models.AnimationControllerLayerSpec
import ru.hollowhorizon.hollowengine.common.models.AnimationControllerStateSpec
import ru.hollowhorizon.hollowengine.common.models.AnimationControllerTransitionSpec
import ru.hollowhorizon.hollowengine.common.models.AnimationExpression
import ru.hollowhorizon.hollowengine.common.models.Animator
import ru.hollowhorizon.hollowengine.common.models.AnimatorStateType
import ru.hollowhorizon.hollowengine.common.models.AnimatorStateTypes
import ru.hollowhorizon.hollowengine.common.models.ClipStateSpec
import ru.hollowhorizon.hollowengine.common.models.UnknownAnimatorStateSpec
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.serialization.deserialize
import ru.hollowhorizon.hollowengine.common.utils.serialization.serialize
import kotlin.test.assertEquals
import kotlin.test.assertIs

@Serializable
@SerialName(AddonStateId)
private data class AddonStateSpec(
    override val id: String = "limp",
    val density: Float = 1050f,
) : AnimationControllerStateSpec() {
    override fun withId(id: String) = copy(id = id)
}

private const val AddonStateId = "test:animator/state/addon"

class AnimatorStateTypesTests {
    private var registration: ExtensionHandle? = null

    private val animator = Animator(
        layers = listOf(
            AnimationControllerLayerSpec(
                id = "controller",
                states = listOf(
                    ClipStateSpec(id = "idle", animation = "idle"),
                    AddonStateSpec(id = "limp", density = 980f),
                ),
                transitions = listOf(
                    AnimationControllerTransitionSpec(
                        from = "idle",
                        to = "limp",
                        condition = AnimationExpression("d.dead"),
                    )
                ),
            )
        )
    )

    @AfterEach
    fun cleanup() {
        registration?.close()
        registration = null
    }

    private fun registerAddonType() {
        registration = AnimatorStateTypes.register(
            AnimatorStateType(
                id = AddonStateId,
                specClass = AddonStateSpec::class,
                serializer = AddonStateSpec.serializer(),
                titleKey = "test.addon_state",
            )
        )
    }

    @Test
    fun `a registered state type round-trips`() {
        registerAddonType()

        val restored = NBTFormat.deserialize<Animator, Tag>(NBTFormat.serialize(animator))

        assertEquals(animator, restored)
    }

    @Test
    fun `a state whose type is gone is kept as it was stored`() {
        registerAddonType()
        val stored = NBTFormat.serialize<Animator, Tag>(animator)
        registration?.close()
        registration = null

        val withoutAddon = NBTFormat.deserialize<Animator, Tag>(stored)
        val controller = assertIs<AnimationControllerLayerSpec>(withoutAddon.layers.single())
        val unknown = assertIs<UnknownAnimatorStateSpec>(controller.states[1])
        assertEquals(AddonStateId, unknown.typeId)
        assertEquals("limp", unknown.id)
        assertEquals(980f, unknown.payload.getFloat("density"))

        assertEquals(stored, NBTFormat.serialize<Animator, Tag>(withoutAddon))

        registerAddonType()
        assertEquals(animator, NBTFormat.deserialize<Animator, Tag>(stored))
    }

    @Test
    fun `renaming an unknown state keeps the rest of it`() {
        registerAddonType()
        val stored = NBTFormat.serialize<Animator, Tag>(animator)
        registration?.close()
        registration = null

        val controller = assertIs<AnimationControllerLayerSpec>(
            NBTFormat.deserialize<Animator, Tag>(stored).layers.single()
        )
        val renamed = assertIs<UnknownAnimatorStateSpec>(controller.states[1].withId("knocked_down"))

        assertEquals("knocked_down", renamed.id)
        assertEquals(980f, renamed.payload.getFloat("density"))
    }
}
