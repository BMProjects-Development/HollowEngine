import net.minecraft.nbt.Tag
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.colliders.ColliderContact
import ru.hollowhorizon.hollowengine.common.colliders.ColliderModes
import ru.hollowhorizon.hollowengine.common.colliders.ColliderOverride
import ru.hollowhorizon.hollowengine.common.colliders.CollidersComponent
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.serialization.deserialize
import ru.hollowhorizon.hollowengine.common.utils.serialization.serialize
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColliderOverrideTest {
    @Test
    fun `an override changes only what it sets`() {
        val rig = ColliderModes(hit = true, interact = true, contact = ColliderContact.PUSH, force = 0.5f)

        val changed = ColliderOverride(contact = ColliderContact.SOLID).applyTo(rig)

        assertEquals(ColliderModes(hit = true, interact = true, contact = ColliderContact.SOLID, force = 0.5f), changed)
    }

    @Test
    fun `an override with nothing set is empty`() {
        assertTrue(ColliderOverride().isEmpty)
        assertEquals(ColliderModes(), ColliderOverride().applyTo(ColliderModes()))
    }

    @Test
    fun `overrides keep what was left unset through saving`() {
        val component = CollidersComponent(
            mapOf(
                "door" to ColliderOverride(enabled = false),
                "shield" to ColliderOverride(hit = false, force = 2f),
            ),
        )

        assertEquals(component, NBTFormat.deserialize<CollidersComponent, Tag>(NBTFormat.serialize(component)))
    }
}
