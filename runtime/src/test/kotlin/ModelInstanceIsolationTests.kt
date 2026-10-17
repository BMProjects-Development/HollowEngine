import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.internal.*
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.applyAnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelInstance
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelInstanceMaterials
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.attachments.components.AnimationsComponent
import ru.hollowhorizon.hollowengine.common.models.AnimationPlayMode
import ru.hollowhorizon.hollowengine.common.models.ClipAnimationLayerSpec
import ru.hollowhorizon.hollowengine.common.models.LayerBlendMode
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class ModelInstanceIsolationTests {
    @Test
    fun `material changes stay inside one model instance`() {
        val sourceMaterial = Material(texture = "test:source".rl)
        val model = modelWith(sourceMaterial, floatArrayOf())
        val first = ModelInstanceMaterials(model)
        val second = ModelInstanceMaterials(model)

        first.values.single().texture = "test:replacement".rl

        assertNotSame(sourceMaterial, first.values.single())
        assertEquals("test:source".rl, sourceMaterial.texture)
        assertEquals("test:source".rl, second.values.single().texture)
    }

    @Test
    fun `morph weights stay inside one runtime node`() {
        val sourceWeights = floatArrayOf(0.25f, 0.75f)
        val definition = modelWith(Material(), sourceWeights).scenes.single().nodes.single()
        val first = RuntimeNode(definition, null)
        val second = RuntimeNode(definition, null)
        val pose = AnimationPose().apply {
            bone(definition.index).weights = floatArrayOf(1f, 0f)
        }

        applyAnimationPose(
            pose = pose,
            nodes = mapOf(definition.index to first),
            blendMode = LayerBlendMode.Override,
            weight = 1f,
        )

        assertContentEquals(floatArrayOf(1f, 0f), first.morphWeights)
        assertContentEquals(sourceWeights, second.morphWeights)
        assertContentEquals(sourceWeights, definition.mesh!!.primitives.single().weights)
    }

    @Test
    fun `model animation advances once per render frame`() {
        val animation = AnimationClip(name = "clock", nodes = emptyMap(), duration = 10f)
        val model = Model(
            scene = 0,
            scenes = listOf(
                Scene(
                    listOf(
                        NodeDefinition(
                            index = 0,
                            name = "Root",
                            children = mutableListOf(),
                            transform = TrsTransformF(),
                        )
                    )
                )
            ),
            materials = emptySet(),
            animations = listOf(animation),
        )
        val instance = ModelInstance(ModelAttachment(MutableStateFlow(model), null))
        instance.animator.configure(
            model = null,
            animations = AnimationsComponent(
                clips = listOf(
                    ClipAnimationLayerSpec(
                        id = "clock",
                        animation = animation.name,
                        playMode = AnimationPlayMode.Loop,
                    )
                )
            )
        )

        val step = 20f / 60f
        repeat(31) { frame -> instance.update(contextAt(frame * step), frame = frame.toLong()) }
        val halfway = instance.animator.layerTime("clock")!!

        instance.update(contextAt(60f), frame = 30L)

        instance.update(contextAt(30 * step), frame = 31L)

        assertEquals(0.5f, halfway, 0.0001f)
        assertEquals(halfway, instance.animator.layerTime("clock")!!, 0.0001f)
    }

    private fun contextAt(ticks: Float) = AnimatorEvaluationContext().also { it.time = ticks }

    private fun modelWith(material: Material, morphWeights: FloatArray): Model {
        val primitive = Primitive(
            material = material,
            morphTargets = List(morphWeights.size) { emptyMap() },
            weights = morphWeights.copyOf(),
        )
        val node = NodeDefinition(
            index = 0,
            name = "Root",
            children = mutableListOf(),
            transform = TrsTransformF(),
            mesh = Mesh(listOf(primitive), morphWeights.copyOf()),
        )
        return Model(
            scene = 0,
            scenes = listOf(Scene(listOf(node))),
            materials = setOf(material),
        )
    }
}
