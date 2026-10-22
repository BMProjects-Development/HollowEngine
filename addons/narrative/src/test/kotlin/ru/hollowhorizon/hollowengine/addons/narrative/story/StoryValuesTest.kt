package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.serialization.Serializable
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.StringTag
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import ru.hollowhorizon.hollowengine.common.npcs.registry.NpcRef
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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

        val lambda = StageLambda<Unit>("quests/village.kts", "script\$launchStory#1", CompoundTag().apply { putString("who", "alex") })
        val restored = roundTrip<StageLambda<*>>(lambda)
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
}
