
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentPersistencePolicy
import ru.hollowhorizon.hollowengine.common.attachments.components.ai.*
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySerialization
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySnapshot
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.LegacyModelPose
import ru.hollowhorizon.hollowengine.common.scene.hasSamePoseAs
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.deg
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.nbt.ForQuatF
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `saved model transform migrates into the node pose`() {
        registerDescriptors()
        val transform = TrsTransformF().translate(1f, 2f, 3f).rotate(30f.deg, Vec3f.Y_AXIS).scale(Vec3f(2f, 3f, 4f))
        val marker = BasicComponent("keep")
        val oldFormat = NBTFormat(SerializersModule {
            include(EntitySerialization.serializersModule())
            polymorphic(Any::class) { subclass(LegacyModelPose::class, LegacyModelPose.serializer()) }
        })
        val old = oldFormat.serialize(EntitySnapshot.serializer(), EntitySnapshot(listOf(marker, LegacyModelPose(transform))))

        val snapshot = EntitySerialization.deserializeFromNbt(old)
        assertEquals(listOf(marker), snapshot.components)
        assertTrue(transform.hasSamePoseAs(snapshot.modelPose))
        assertEquals(snapshot, EntitySerialization.deserializeFromNbt(EntitySerialization.serializeToNbt(snapshot)))
        assertEquals(snapshot, EntitySerialization.deserializeFromByteBuf(EntitySerialization.serializeToByteBuf(snapshot)))
        assertNull(ComponentDescriptorRegistry.descriptorOrNull("hollowengine:transform".rl))
    }

    @Test
    fun `model pose reads the existing snapshot fields`() {
        val position = Vec3f(1f, 2f, 3f)
        val pose = CompoundTag().apply {
            put("position", NBTFormat.serialize(Vec3f.serializer(), position))
            put("rotation", NBTFormat.serialize(ForQuatF, QuatF.IDENTITY))
        }
        val saved = CompoundTag().apply { put("modelPose", pose) }
        val decoded = EntitySerialization.deserializeFromNbt(saved)

        assertEquals<Vec3f?>(position, decoded.modelPose?.translation)
        assertEquals<Vec3f?>(Vec3f.ONES, decoded.modelPose?.scale)
    }

    @Test
    fun `pose reset survives serialization separately from an absent pose`() {
        val reset = EntitySnapshot(modelPose = TrsTransformF())
        val resetReceived = EntitySerialization.deserializeFromByteBuf(EntitySerialization.serializeToByteBuf(reset))
        val absentReceived = EntitySerialization.deserializeFromByteBuf(EntitySerialization.serializeToByteBuf(EntitySnapshot()))

        assertEquals(reset, resetReceived)
        assertNull(absentReceived.modelPose)
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
    fun `a component the game no longer knows is skipped`() {
        registerDescriptors()
        val saved = EntitySerialization.serializeToNbt(
            EntitySnapshot(components = listOf(LooseComponent("removed from the engine"), BasicComponent("keep"))),
        )
        ComponentDescriptorRegistry.unregisterDescriptor(looseId)

        val decoded = EntitySerialization.deserializeFromNbt(saved)

        assertEquals(listOf(BasicComponent("keep")), decoded.components)
    }

    @Test
    fun `a component the client does not know is skipped`() {
        registerDescriptors()
        val sent = EntitySerialization.serializeToByteBuf(
            EntitySnapshot(components = listOf(LooseComponent("server only"), BasicComponent("keep"))),
        )
        ComponentDescriptorRegistry.unregisterDescriptor(looseId)

        val received = EntitySerialization.deserializeFromByteBuf(sent)

        assertEquals(listOf(BasicComponent("keep")), received.components)
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
private data class BasicComponent(val value: String)

@Serializable
private data class LooseComponent(val value: String)
