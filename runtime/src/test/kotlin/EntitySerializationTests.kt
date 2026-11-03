
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentPersistencePolicy
import ru.hollowhorizon.hollowengine.common.attachments.components.UnknownComponent
import ru.hollowhorizon.hollowengine.common.attachments.components.ai.*
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySerialization
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySnapshot
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs

class EntitySerializationTests {
    private val basicId = "test:basic_component".rl
    private val looseId = "test:loose_component".rl
    private val lookAtId = "hollowengine:look_at_target".rl
    private val patrolId = "hollowengine:patrol_path".rl
    private val registeredForTest = mutableSetOf<ResourceLocation>()

    @AfterEach
    fun cleanup() {
        ComponentDescriptorRegistry.unregisterDescriptor(basicId)
        ComponentDescriptorRegistry.unregisterDescriptor(looseId)
        registeredForTest.forEach(ComponentDescriptorRegistry::unregisterDescriptor)
        registeredForTest.clear()
    }

    @Test
    fun `snapshot roundtrip preserves components across yaml and nbt`() {
        registerDescriptors()
        val snapshot = EntitySnapshot(
            components = listOf(BasicComponent("hello"), LooseComponent("temp")),
        )

        val yamlDecoded = EntitySerialization.deserializeFromYaml(EntitySerialization.serializeToYaml(snapshot))
        val nbtDecoded = EntitySerialization.deserializeFromNbt(EntitySerialization.serializeToNbt(snapshot))

        assertEquals(snapshot, yamlDecoded)
        assertEquals(snapshot, nbtDecoded)
    }

    @Test
    fun `drop loose on death removes only marked components`() {
        registerDescriptors()
        val snapshot = EntitySnapshot(
            components = listOf(BasicComponent("keep"), LooseComponent("drop")),
        )

        val filtered = snapshot.dropLooseOnDeathComponents()

        assertEquals(listOf(BasicComponent("keep")), filtered.components)
    }

    @Test
    fun `safe yaml deserialize returns null for unknown component`() {
        registerDescriptors()
        val yaml = """
            components:
              - type: test:missing_component
                value: nope
        """.trimIndent()

        assertNull(EntitySerialization.tryDeserializeFromYaml(yaml, "test unknown component"))
    }

    @Test
    fun `a missing component survives saving without its addon and restores when registered`() {
        registerDescriptors()
        val saved = EntitySerialization.serializeToNbt(
            EntitySnapshot(components = listOf(LooseComponent("removed from the engine"), BasicComponent("keep"))),
        )
        ComponentDescriptorRegistry.unregisterDescriptor(looseId)

        val decoded = EntitySerialization.deserializeFromNbt(saved)

        assertIs<UnknownComponent>(decoded.components.first())
        assertEquals(looseId, decoded.componentById().keys.first())
        assertEquals(BasicComponent("keep"), decoded.components.last())
        val savedAgain = EntitySerialization.serializeToNbt(decoded)
        assertEquals(saved, savedAgain)

        registerDescriptors()
        assertEquals(
            listOf(LooseComponent("removed from the engine"), BasicComponent("keep")),
            EntitySerialization.deserializeFromNbt(savedAgain).components,
        )
    }

    @Test
    fun `a missing component survives a network relay without its addon`() {
        registerDescriptors()
        val sent = EntitySerialization.serializeToByteBuf(
            EntitySnapshot(components = listOf(LooseComponent("server only"), BasicComponent("keep"))),
        )
        ComponentDescriptorRegistry.unregisterDescriptor(looseId)

        try {
            val received = EntitySerialization.deserializeFromByteBuf(sent)
            assertIs<UnknownComponent>(received.components.first())
            assertEquals(BasicComponent("keep"), received.components.last())
            val relayed = EntitySerialization.serializeToByteBuf(received)
            try {
                registerDescriptors()
                assertEquals(
                    listOf(LooseComponent("server only"), BasicComponent("keep")),
                    EntitySerialization.deserializeFromByteBuf(relayed).components,
                )
            } finally {
                relayed.release()
            }
        } finally {
            sent.release()
        }
    }

    @Test
    fun `ai components roundtrip preserves patrol paths and entity references`() {
        registerAiDescriptorsIfNeeded()
        assertNotNull(ComponentDescriptorRegistry.descriptorOrNull(lookAtId))
        assertNotNull(ComponentDescriptorRegistry.descriptorOrNull(patrolId))

        val snapshot = EntitySnapshot(
            components = listOf(
                LookAtTargetComponent(
                    enabled = true,
                    targetMode = LookTargetMode.ENTITY,
                    targetEntity = EntityReference(
                        uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                        level = "minecraft:overworld".rl,
                    ),
                    targetPosition = Vec3(1.0, 2.0, 3.0),
                    yawSpeed = 25f,
                    pitchSpeed = 15f,
                ),
                PatrolPathComponent(
                    enabled = true,
                    points = listOf(
                        PatrolPoint(Vec3(1.0, 64.0, 1.0), waitTicks = 10, lookAtNextPoint = true),
                        PatrolPoint(Vec3(4.0, 64.0, 4.0), waitTicks = 0, lookAtNextPoint = false),
                    ),
                    loop = true,
                    speed = 1.25f,
                    arrivalRadius = 1.0f,
                )
            )
        )

        val yamlDecoded = EntitySerialization.deserializeFromYaml(EntitySerialization.serializeToYaml(snapshot))
        val nbtDecoded = EntitySerialization.deserializeFromNbt(EntitySerialization.serializeToNbt(snapshot))

        val yamlLook = yamlDecoded.components.filterIsInstance<LookAtTargetComponent>().single()
        val yamlPatrol = yamlDecoded.components.filterIsInstance<PatrolPathComponent>().single()
        val nbtLook = nbtDecoded.components.filterIsInstance<LookAtTargetComponent>().single()
        val nbtPatrol = nbtDecoded.components.filterIsInstance<PatrolPathComponent>().single()

        assertEquals(LookTargetMode.ENTITY, yamlLook.targetMode)
        assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), yamlLook.targetEntity.uuid)
        assertEquals("minecraft:overworld".rl, yamlLook.targetEntity.level)
        assertEquals(2, yamlPatrol.points.size)
        assertEquals(1.0, yamlPatrol.points.first().position.x)
        assertEquals(64.0, yamlPatrol.points.first().position.y)
        assertEquals(4.0, yamlPatrol.points.last().position.z)
        assertEquals(false, yamlPatrol.points.last().lookAtNextPoint)

        assertEquals(LookTargetMode.ENTITY, nbtLook.targetMode)
        assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), nbtLook.targetEntity.uuid)
        assertEquals("minecraft:overworld".rl, nbtLook.targetEntity.level)
        assertEquals(2, nbtPatrol.points.size)
        assertEquals(10, nbtPatrol.points.first().waitTicks)
        assertEquals(1.25f, nbtPatrol.speed)
        assertEquals(1.0f, nbtPatrol.arrivalRadius)
    }

    private fun registerDescriptors() {
        if (ComponentDescriptorRegistry.descriptorOrNull(basicId) == null) {
            ComponentDescriptorRegistry.register(
                ComponentDescriptor(
                    id = basicId,
                    value = BasicComponent::class,
                    serializer = BasicComponent.serializer(),
                )
            )
        }
        if (ComponentDescriptorRegistry.descriptorOrNull(looseId) == null) {
            ComponentDescriptorRegistry.register(
                ComponentDescriptor(
                    id = looseId,
                    value = LooseComponent::class,
                    serializer = LooseComponent.serializer(),
                    persistencePolicy = ComponentPersistencePolicy.LOOSE_ON_DEATH,
                )
            )
        }
    }

    private fun registerAiDescriptorsIfNeeded() {
        ensureRegistered(lookAtId, LookAtTargetComponent::class, LookAtTargetComponent.serializer())
        ensureRegistered(patrolId, PatrolPathComponent::class, PatrolPathComponent.serializer())
    }

    private fun <T : Any> ensureRegistered(id: ResourceLocation, type: kotlin.reflect.KClass<T>, serializer: kotlinx.serialization.KSerializer<T>) {
        if (ComponentDescriptorRegistry.descriptorOrNull(id) != null) return
        ComponentDescriptorRegistry.register(
            ComponentDescriptor(
                id = id,
                value = type,
                serializer = serializer,
            )
        )
        registeredForTest += id
    }
}

@Serializable
@SerialName("test:basic_component")
private data class BasicComponent(val value: String)

@Serializable
@SerialName("test:loose_component")
private data class LooseComponent(val value: String)
