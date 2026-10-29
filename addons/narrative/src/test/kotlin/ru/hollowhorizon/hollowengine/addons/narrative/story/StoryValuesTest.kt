package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.serialization.Serializable
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.LongTag
import net.minecraft.nbt.StringTag
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.npcs.navigation.Facing
import ru.hollowhorizon.hollowengine.common.npcs.navigation.MoveOptions
import ru.hollowhorizon.hollowengine.common.npcs.navigation.Zone
import ru.hollowhorizon.hollowengine.common.npcs.registry.NpcRef
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class StoryValuesTest {
    @Serializable
    data class Loot(val name: String, val count: Int)

    @AfterTest
    fun restoreResolver() {
        StoryValues.entities = { _, _ -> null }
    }

    private inline fun <reified T> roundTrip(value: T): T {
        val tag = CompoundTag()
        StoryValues.put(tag, "value", storySerializer<T>(), value)
        @Suppress("UNCHECKED_CAST")
        return StoryValues.get(tag, "value", storySerializer<T>()) as T
    }

    @Test
    fun `a duration is kept as milliseconds`() {
        val tag = CompoundTag()
        StoryValues.put(tag, "value", storySerializer<Duration>(), 1500.milliseconds)

        assertEquals(1500L, (tag.get("value") as LongTag).asLong)
        assertEquals(1500.milliseconds, roundTrip(1500.milliseconds))
    }

    @Test
    fun `move options come back with their facing`() {
        val toward = MoveOptions(speed = 1.5, facing = Facing.Toward(UUID(1, 2)))
        val point = MoveOptions(facing = Facing.at(Vec3(1.0, 2.0, 3.0)), avoid = listOf(Zone.Sphere(Vec3.ZERO, 2.0)))

        assertEquals(toward, roundTrip(toward))
        assertEquals(point, roundTrip(point))
        assertEquals(MoveOptions(), roundTrip(MoveOptions()))
    }

    @Test
    fun `plain values and collections of them come back as they were`() {
        assertEquals(42, roundTrip(42))
        assertEquals("text", roundTrip("text"))
        assertNull(roundTrip<String?>(null))
        assertEquals(listOf("a", "b"), roundTrip(listOf("a", "b")))
        assertEquals(mapOf("gold" to 3), roundTrip(mapOf("gold" to 3)))
        assertEquals("a" to 1, roundTrip("a" to 1))
        assertEquals(Loot("sword", 1), roundTrip(Loot("sword", 1)))
    }

    @Test
    fun `NPC references and stage lambdas are saved by what identifies them`() {
        val uuid = UUID.randomUUID()
        assertEquals(uuid, roundTrip(NpcRef(uuid)).uuid)

        val lambda = StageLambda2<String, Int, Unit>("quests/village.kts", "script\$launchStory#1", CompoundTag().apply { putString("who", "alex") })
        val restored = roundTrip<StageLambda>(lambda)
        assertIs<StageLambda2<*, *, *>>(restored, "the number of parameters comes back with the lambda")
        assertEquals(lambda.instance, restored.instance)
        assertEquals(lambda.stage, restored.stage)
        assertEquals(lambda.args, restored.args)
    }

    @Test
    fun `entities are kept as references, at any depth, and found again when read`() {
        val uuid = UUID.randomUUID()
        val asked = ArrayList<Pair<UUID, String>>()
        StoryValues.entities = { id, type ->
            asked += id to type.simpleName!!
            null
        }
        assertFailsWith<StoryEntityMissing> { StoryValues.decode(storySerializer<Player>(), StringTag.valueOf(uuid.toString())) }
        assertEquals(listOf(uuid to "Player"), asked)

        val listDescriptor = storySerializer<List<Entity>>().descriptor
        assertEquals("story.entity", listDescriptor.getElementDescriptor(0).serialName)
        val mapDescriptor = storySerializer<Map<String, Player?>>().descriptor
        assertEquals("story.entity", mapDescriptor.getElementDescriptor(1).serialName.removeSuffix("?"))
    }

    @Test
    fun `a value that cannot be saved is reported by its type`() {
        val failure = assertFailsWith<StoryException> { storySerializer<Thread>() }
        assertEquals(true, failure.message?.contains("java.lang.Thread"))
    }

    @Test
    fun `zones come back equal to themselves, so an undone avoid finds the zone it added`() {
        val zones = listOf(Zone.sphere(Vec3(1.0, 2.0, 3.0), 4.0, cost = 5f), Zone.box(Vec3(0.0, 0.0, 0.0), Vec3(2.0, 3.0, 4.0)))
        assertEquals(zones, roundTrip(zones))
    }
}
