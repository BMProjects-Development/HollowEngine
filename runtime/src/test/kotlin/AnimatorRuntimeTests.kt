
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationData
import ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations.Interpolator
import ru.hollowhorizon.hollowengine.client.models.internal.animator.*
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.common.attachments.components.*
import ru.hollowhorizon.hollowengine.common.models.*
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySerialization
import ru.hollowhorizon.hollowengine.common.attachments.snapshot.EntitySnapshot
import ru.hollowhorizon.hollowengine.common.models.ServerModelAnimationMetadata
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockContext
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnimatorRuntimeTests {
    private val animationsId = "hollowengine:animations".rl
    private val hideVanillaModelId = "hollowengine:hide_vanilla_entity_model".rl

    @AfterEach
    fun cleanup() {
        ComponentDescriptorRegistry.unregisterDescriptor(animationsId)
        ComponentDescriptorRegistry.unregisterDescriptor(hideVanillaModelId)
    }

    @Test
    fun `once playback clamps and marks ended`() {
        val state = ClipPlayback()

        val sampleTime = state.advance(
            duration = 1f,
            playMode = AnimationPlayMode.Once,
            speed = 1f,
            deltaTime = 2f,
        )

        assertEquals(1f, sampleTime)
        assertEquals(1f, state.time)
        assertTrue(state.ended)
        assertEquals(1f, state.advance(1f, AnimationPlayMode.Once, 1f, 1f))
    }

    @Test
    fun `loop playback wraps without ending`() {
        val state = ClipPlayback()

        val sampleTime = state.advance(
            duration = 1f,
            playMode = AnimationPlayMode.Loop,
            speed = 1f,
            deltaTime = 1.25f,
        )

        assertEquals(0.25f, sampleTime)
        assertEquals(0.25f, state.time)
        assertFalse(state.ended)
    }

    @Test
    fun `clamp forever holds final frame without ending`() {
        val state = ClipPlayback()

        val sampleTime = state.advance(
            duration = 1f,
            playMode = AnimationPlayMode.ClampForever,
            speed = 1f,
            deltaTime = 2f,
        )

        assertEquals(1f, sampleTime)
        assertEquals(1f, state.time)
        assertFalse(state.ended)
    }

    @Test
    fun `ping pong reflects time and toggles direction`() {
        val state = ClipPlayback()

        val sampleTime = state.advance(
            duration = 1f,
            playMode = AnimationPlayMode.PingPong,
            speed = 1f,
            deltaTime = 1.25f,
        )

        assertEquals(0.75f, sampleTime)
        assertEquals(0.75f, state.time)
        assertTrue(state.reversed)
        assertFalse(state.ended)
    }

    @Test
    fun `component helpers replace and remove layers by stable ids`() {
        val first = clip(id = "manual:idle", animation = "idle")
        val replacement = clip(id = "manual:idle", animation = "walk", priority = 2)
        val attack = clip(id = "manual:attack", animation = "attack")

        val animations = AnimationsComponent()
            .withClip(first)
            .withClip(attack)
            .withClip(replacement)
            .withoutClip("attack")

        assertEquals(listOf(replacement), animations.clips)
    }

    @Test
    fun `animations component roundtrips through snapshot serializers`() {
        registerAnimationsDescriptor()
        registerHideVanillaModelDescriptor()
        val snapshot = EntitySnapshot(
            components = listOf(
                AnimationsComponent(
                    clips = listOf(
                        clip(
                            id = "manual:wave",
                            animation = "wave",
                            priority = 7,
                            weight = AnimationExpression("weight_value"),
                            blendMode = LayerBlendMode.Additive,
                            playMode = AnimationPlayMode.PingPong,
                        )
                    ),
                ),
                HideVanillaEntityModelComponent(),
            ),
        )

        val yamlDecoded = EntitySerialization.deserializeFromYaml(EntitySerialization.serializeToYaml(snapshot))
        val nbtDecoded = EntitySerialization.deserializeFromNbt(EntitySerialization.serializeToNbt(snapshot))

        assertEquals(snapshot, yamlDecoded)
        assertEquals(snapshot, nbtDecoded)
    }

    @Test
    fun `standard player preset expressions evaluate through kotlite`() {
        val animator = StandardPlayerAnimatorPreset.create()
        val controller = animator.layers.filterIsInstance<AnimationControllerLayerSpec>().single()
        val procedural = animator.layers.filterIsInstance<ProceduralLayerSpec>().single()
        val evaluator = AnimatorExpressionEvaluator
        evaluator.prepareNow(animator)
        val context = animationContext(
            deltaTime = 0.05f,
            time = 10f,
            values = mapOf(
                "is_alive" to 1f,
                "is_sneaking" to 0f,
                "is_sprinting" to 1f,
                "is_on_ground" to 1f,
                "horizontal_speed" to 0.12f,
                "movement_animation_speed" to -1.2f,
                "velocity_y" to 0f,
                "head_body_y_delta" to 30f,
                "head_x_rotation" to -10f,
            ),
        )

        val runTransition = controller.transitions.single { it.to == "run" }
        val idleTransition = controller.transitions.single { it.to == "idle" }
        val walkState = controller.states.single { it.id == "walk" } as ClipStateSpec
        val runState = controller.states.single { it.id == "run" } as ClipStateSpec
        val headTransform = procedural.transforms.single { it.bone == "Head" }
        val leftEyeTransform = procedural.transforms.single { it.bone == "LeftEye" }
        val rightEyeTransform = procedural.transforms.single { it.bone == "RightEye" }

        assertTrue(evaluator.boolean(runTransition.condition, context))
        assertFalse(evaluator.boolean(idleTransition.condition, context))
        assertEquals(-0.6f, evaluator.float(walkState.speed, context), 0.0001f)
        assertEquals(-0.6f, evaluator.float(runState.speed, context), 0.0001f)
        assertEquals(6.5f, evaluator.vector(headTransform.rotation!!, context).x, 0.0001f)
        assertEquals(-18.6f, evaluator.vector(headTransform.rotation, context).y, 0.0001f)
        assertEquals(0f, evaluator.vector(leftEyeTransform.translation!!, context).x, 0.0001f)
        assertEquals(0.0648f, evaluator.vector(rightEyeTransform.translation!!, context).x, 0.0001f)
        assertEquals(-0.01f, evaluator.vector(leftEyeTransform.translation, context).y, 0.0001f)
    }

    @Test
    fun `negative loop speed samples animation backwards`() {
        val state = ClipPlayback()

        val sampleTime = state.advance(
            duration = 1f,
            playMode = AnimationPlayMode.Loop,
            speed = -1f,
            deltaTime = 0.25f,
        )

        assertEquals(0.75f, sampleTime)
        assertEquals(0.75f, state.time)
        assertFalse(state.ended)
    }

    @Test
    fun `layer fade in scales clip influence`() {
        val node = testNode()
        val animator = ModelAnimator()
        animator.configure(
            model = null,
            AnimationsComponent(clips = listOf(clip(id = "manual:wave", animation = "wave").copy(fadeIn = 1f)))
        )

        animator.step(node, seconds = 0.5f)

        assertEquals(0.5f, node.transform.translation.x, 0.0001f)
    }

    @Test
    fun `configure updates clip spec without resetting playback`() {
        val node = testNode()
        val animator = ModelAnimator()
        val layer = clip(id = "manual:wave", animation = "wave", playMode = AnimationPlayMode.Loop)
        animator.configure(model = null, animations = AnimationsComponent(clips = listOf(layer)))
        animator.step(node, seconds = 0.5f)

        animator.configure(
            model = null,
            AnimationsComponent(
                clips = listOf(
                    layer.copy(
                        weight = AnimationExpression("0.5"),
                        priority = 2,
                    )
                )
            )
        )

        assertEquals(0.5f, animator.layerTime(layer.id)!!, 0.0001f)
        animator.step(node, seconds = 0.25f)
        assertEquals(0.75f, animator.layerTime(layer.id)!!, 0.0001f)
        assertEquals(0.5f, node.transform.translation.x, 0.0001f)
    }

    @Test
    fun `configure updates controller spec without resetting its state time`() {
        val node = testNode()
        val animator = ModelAnimator()
        val layer = AnimationControllerLayerSpec(
            id = "controller:preview",
            states = listOf(ClipStateSpec(id = "wave", animation = "wave")),
            entryState = "wave",
        )
        animator.configure(model = Animator(layers = listOf(layer)), animations = null)
        animator.step(node, seconds = 0.5f)

        animator.configure(
            model = Animator(layers = listOf(layer.copy(weight = AnimationExpression("0.5")))),
            animations = null,
        )

        assertEquals(0.5f, animator.layerTime(layer.id)!!, 0.0001f)
        animator.step(node, seconds = 0.25f)
        assertEquals(0.75f, animator.layerTime(layer.id)!!, 0.0001f)
        assertEquals(0.5f, node.transform.translation.x, 0.0001f)
    }

    /** A one-shot layer keeps posing while it fades, and is gone once the fade is over. */
    @Test
    fun `once layer fades out after final frame`() {
        val node = testNode()
        val animator = ModelAnimator()
        animator.configure(
            model = null,
            AnimationsComponent(clips = listOf(clip(id = "manual:wave", animation = "wave").copy(fadeOut = 0.5f)))
        )

        animator.step(node, seconds = 0.75f)
        animator.step(node, seconds = 0.5f)
        assertEquals(0.5f, node.transform.translation.x, 0.0001f)

        animator.step(node, seconds = 0.5f)
        assertTrue(animator.isEmpty)
    }
    
    @Test
    fun `stopping a clip fades it out instead of dropping it`() {
        clockTicks = 0f
        val node = testNode()
        val animator = ModelAnimator()
        val playing = clip(id = "npc:wave", animation = "wave", playMode = AnimationPlayMode.Loop)
        animator.configure(model = null, animations = AnimationsComponent(clips = listOf(playing)))
        animator.step(node, seconds = 0.5f)

        val stopping = playing.copy(
            fadeOut = 0.5f,
            stopAtGameTime = clockTicks.toLong(),
            removeOnEnd = false,
        )
        animator.configure(model = null, animations = AnimationsComponent(clips = listOf(stopping)))

        animator.step(node, seconds = 0f)
        assertEquals(1f, node.transform.translation.x, 0.0001f)

        animator.step(node, seconds = 0.25f)
        assertEquals(0.5f, node.transform.translation.x, 0.0001f)

        animator.step(node, seconds = 0.25f)
        assertTrue(animator.isEmpty)
    }

    /**
     * A state that clamps leaves its time pinned at the end of the clip. Coming back to it has to
     * start the clip over, or the second visit shows nothing but the final frame - and every exitTime
     * measured from that state fires the moment it is entered.
     */
    @Test
    fun `re-entering a state restarts its clip`() {
        val layer = AnimationControllerLayerSpec(
            id = "controller:crate",
            entryState = "closed",
            states = listOf(
                ClipStateSpec(id = "closed", animation = "wave"),
                ClipStateSpec(
                    id = "opening",
                    animation = "wave",
                    playMode = AnimationPlayMode.ClampForever,
                ),
            ),
            transitions = listOf(
                AnimationControllerTransitionSpec(from = "closed", to = "opening", condition = AnimationExpression("v.open > 0")),
                AnimationControllerTransitionSpec(from = "opening", to = "closed", condition = AnimationExpression("v.open <= 0")),
            ),
        )
        AnimatorExpressionEvaluator.prepareNow(Animator(layers = listOf(layer)))
        val controllerLayer = ControllerLayer(layer)
        val target = PoseTarget(emptyMap(), mapOf("wave" to waveAnimation()))

        fun step(open: Float, frames: Int) = repeat(frames) {
            controllerLayer.sample(target, animationContext(deltaTime = 0.25f, values = mapOf("open" to open)))
        }

        // Open, run the one-second clip past its end, then close again.
        step(open = 1f, frames = 8)
        assertEquals("opening", controllerLayer.controller.stateId)
        assertEquals(1f, controllerLayer.controller.stateTime, 0.0001f)
        step(open = 0f, frames = 2)
        assertEquals("closed", controllerLayer.controller.stateId)

        step(open = 1f, frames = 1)
        assertEquals("opening", controllerLayer.controller.stateId)
        assertEquals(0.25f, controllerLayer.controller.stateTime, 0.0001f, "the clip has to start over")
    }

    @Test
    fun `controller keeps current state when it is the highest priority matching transition`() {
        val layer = AnimationControllerLayerSpec(
            id = "controller:locomotion",
            entryState = "run",
            states = listOf(
                ClipStateSpec(id = "walk", animation = "walk"),
                ClipStateSpec(id = "run", animation = "run"),
            ),
            transitions = listOf(
                AnimationControllerTransitionSpec(
                    from = ANY_STATE,
                    to = "run",
                    condition = AnimationExpression("is_sprinting != 0.0 && horizontal_speed > 0.02"),
                    priority = 70,
                ),
                AnimationControllerTransitionSpec(
                    from = ANY_STATE,
                    to = "walk",
                    condition = AnimationExpression("horizontal_speed > 0.02"),
                    priority = 50,
                ),
            ),
        )

        val controllerLayer = ControllerLayer(layer)
        val target = PoseTarget(emptyMap(), emptyMap())
        val context = animationContext(
            time = 1f,
            values = mapOf("is_sprinting" to 1f, "horizontal_speed" to 0.5f),
        ).also { it.deltaTime = 0.05f }

        controllerLayer.sample(target, context)

        assertEquals("run", controllerLayer.controller.stateId)
    }

    @Test
    fun `animation expressions reuse compiled evaluator with changed context`() {
        val evaluator = AnimatorExpressionEvaluator
        val expression = AnimationExpression("horizontal_speed > 0.02 && is_sprinting != 0.0")
        evaluator.prepareNow(listOf(expression.source))

        assertTrue(
            evaluator.boolean(
                expression,
                animationContext(deltaTime = 0.05f, time = 1f, values = mapOf("horizontal_speed" to 0.12f, "is_sprinting" to 1f)),
            )
        )
        assertFalse(
            evaluator.boolean(
                expression,
                animationContext(deltaTime = 0.05f, time = 2f, values = mapOf("horizontal_speed" to 0.0f, "is_sprinting" to 1f)),
            )
        )
    }

    @Test
    fun `animation expressions can use game time for fade out`() {
        val evaluator = AnimatorExpressionEvaluator
        val expression = AnimationExpression("clamp(1 - (game_time - 10) / 40.0, 0, 1)")
        evaluator.prepareNow(listOf(expression.source))

        assertEquals(
            0.5f,
            evaluator.float(expression, animationContext(deltaTime = 0.05f, time = 1f, values = mapOf("game_time" to 30f))),
            0.0001f,
        )
    }

    @Test
    fun `server model metadata reads animation duration from gltf assets`() {
        val duration = ServerModelAnimationMetadata.animationDuration(StandardPlayerAnimatorPreset.MODEL, "walk")

        assertTrue(duration != null && duration > 0f)
    }

    @Test
    fun `pose translation channels apply as local deltas from base pose`() {
        val baseTransform = TrsTransformF().apply {
            translate(Vec3f(0f, 10f, 0f))
        }
        val node = RuntimeNode(
            NodeDefinition(
                index = 0,
                name = "Bone",
                children = mutableListOf(),
                transform = baseTransform,
            ),
            parent = null,
        )
        val pose = AnimationPose().apply {
            bone(0).translation = Vec3f(0f, 1f, 0f)
        }

        applyAnimationPose(
            pose = pose,
            nodes = mapOf(0 to node),
            blendMode = LayerBlendMode.Override,
            weight = 1f,
        )

        assertEquals(10f, node.definition.baseTransform.translation.y, 0.0001f)
        assertEquals(11f, node.transform.translation.y, 0.0001f)
    }

    private fun clip(
        id: String,
        animation: String,
        priority: Int = 0,
        weight: AnimationExpression = AnimationExpression("1"),
        blendMode: LayerBlendMode = LayerBlendMode.Override,
        playMode: AnimationPlayMode = AnimationPlayMode.Once,
    ) = ClipAnimationLayerSpec(
        id = id,
        animation = animation,
        weight = weight,
        priority = priority,
        blendMode = blendMode,
        playMode = playMode,
    )

    private fun testNode(): RuntimeNode =
        RuntimeNode(
            NodeDefinition(
                index = 0,
                name = "Bone",
                children = mutableListOf(),
                transform = TrsTransformF(),
            ),
            parent = null,
        )

    private fun constantTranslationAnimation(): AnimationClip =
        AnimationClip(
            name = "wave",
            nodes = mapOf(
                0 to AnimationData(
                    translation = ConstantVec3fInterpolator(Vec3f(1f, 0f, 0f), 1f),
                    rotation = null,
                    scale = null,
                    weights = null,
                )
            ),
            duration = 1f,
        )

    private fun registerAnimationsDescriptor() {
        if (ComponentDescriptorRegistry.descriptorOrNull(animationsId) != null) return
        ComponentDescriptorRegistry.register(
            ComponentDescriptor(
                id = animationsId,
                value = AnimationsComponent::class,
                serializer = AnimationsComponent.serializer(),
            )
        )
    }

    private fun registerHideVanillaModelDescriptor() {
        if (ComponentDescriptorRegistry.descriptorOrNull(hideVanillaModelId) != null) return
        ComponentDescriptorRegistry.register(
            ComponentDescriptor(
                id = hideVanillaModelId,
                value = HideVanillaEntityModelComponent::class,
                serializer = HideVanillaEntityModelComponent.serializer(),
            )
        )
    }
}

private class ConstantVec3fInterpolator(
    private val value: Vec3f,
    override val duration: Float,
) : Interpolator<Vec3f> {
    override fun compute(time: Float, context: BedrockContext): Vec3f = value
}

/**
 * A context with the given values pinned. Names that would normally come off an entity fall back to
 * these, which is also how a model preview drives its animations without an entity in the world.
 */
private fun animationContext(
    deltaTime: Float = 0f,
    time: Float = 0f,
    values: Map<String, Float> = emptyMap(),
) = AnimatorEvaluationContext().also { context ->
    context.deltaTime = deltaTime
    context.time = time
    context.gameTime = values["game_time"] ?: time
    context.horizontalSpeed = values["horizontal_speed"] ?: 0f
    context.signedHorizontalSpeed = values["movement_animation_speed"] ?: 0f
    context.headBodyYawDelta = values["head_body_y_delta"] ?: 0f
    context.headPitch = values["head_x_rotation"] ?: 0f
    context.variables.putAll(values)
}

/**
 * Advances [node]'s pose by [seconds] of game time.
 *
 * Two calls because the animator takes its step from the clock rather than from a delta handed to it:
 * the first one only sets the clock, the second one is the frame being tested.
 */
private fun ModelAnimator.step(node: RuntimeNode, seconds: Float) {
    val target = PoseTarget(mapOf(node.definition.index to node), mapOf("wave" to waveAnimation()))
    node.resetPose()
    applyTo(target, animationContext(time = clockTicks))
    clockTicks += seconds * 20f
    node.resetPose()
    applyTo(target, animationContext(time = clockTicks))
}

private var clockTicks = 0f

private fun waveAnimation(): AnimationClip =
    AnimationClip(
        name = "wave",
        nodes = mapOf(
            0 to AnimationData(
                translation = ConstantVec3fInterpolator(Vec3f(1f, 0f, 0f), 1f),
                rotation = null,
                scale = null,
                weights = null,
            )
        ),
        duration = 1f,
    )
