package ru.hollowhorizon.hollowengine.common.models

object StandardPlayerAnimatorPreset {
    const val MODEL = "hollowengine:models/entity/player_model.gltf"

    const val ID = "hollowengine:standard_player"

    private val loopedStates = setOf("idle", "walk", "run", "sneak", "levitation")
    private val clampedStates = setOf("death", "sit", "sleep", "lay")

    fun create(): Animator = Animator(
        layers = listOf(
            AnimationControllerLayerSpec(
                id = "standard_player:locomotion",
                states = listOf("death", "sneak", "run", "levitation", "walk", "idle").map(::stateFor),
                transitions = locomotionTransitions(),
                entryState = "idle",
                priority = 0,
                blendMode = LayerBlendMode.Override,
            ),
            ProceduralLayerSpec(
                id = "standard_player:look_procedural",
                priority = 20,
                blendMode = LayerBlendMode.Additive,
                mask = BoneMask.of("Head", "BodyUp", "LeftArm", "RightArm", "LeftEye", "RightEye"),
                transforms = listOf(
                    ProceduralBoneTransformSpec(
                        bone = "Head",
                        rotation = vector(
                            x = "-head_x_rotation * 0.65",
                            y = "-head_body_y_delta * 0.62",
                        ),
                    ),
                    ProceduralBoneTransformSpec(
                        bone = "LeftEye",
                        translation = vector(
                            x = "clamp(head_body_y_delta, -18, 0) * 0.0036",
                            y = "clamp(head_x_rotation, -10, 10) * 0.0010",
                        ),
                    ),
                    ProceduralBoneTransformSpec(
                        bone = "RightEye",
                        translation = vector(
                            x = "clamp(head_body_y_delta, 0, 18) * 0.0036",
                            y = "clamp(head_x_rotation, -10, 10) * 0.0010",
                        ),
                    ),
                ),
            ),
        )
    )

    private fun stateFor(animation: String): AnimationControllerStateSpec =
        ClipStateSpec(
            id = animation,
            animation = animation,
            playMode = when (animation) {
                in loopedStates -> AnimationPlayMode.Loop
                in clampedStates -> AnimationPlayMode.ClampForever
                else -> AnimationPlayMode.Once
            },
            speed = AnimationExpression(stateSpeedExpression(animation)),
        )

    private fun stateSpeedExpression(animation: String): String =
        when (animation) {
            "walk",
            "run",
            "sneak",
                -> "movement_animation_speed / 2.0"

            else -> "1"
        }

    private fun locomotionTransitions(): List<AnimationControllerTransitionSpec> = listOf(
        transition("death", "death_progress > 0.0", priority = 100, duration = "0.1"),
        transition("sneak", "is_alive != 0.0 && is_sneaking != 0.0", priority = 80),
        transition("run", "is_alive != 0.0 && is_sprinting != 0.0 && horizontal_speed > 0.02", priority = 70),
        transition("levitation", "is_alive != 0.0 && is_on_ground == 0.0 && velocity_y > 0.05", priority = 60),
        transition("walk", "is_alive != 0.0 && horizontal_speed > 0.02", priority = 50),
        transition("idle", "is_alive != 0.0 && horizontal_speed <= 0.02", priority = 0),
    )

    private fun transition(
        to: String,
        condition: String,
        priority: Int,
        duration: String = "0.18",
    ) = AnimationControllerTransitionSpec(
        from = ANY_STATE,
        to = to,
        condition = AnimationExpression(condition),
        duration = AnimationExpression(duration),
        priority = priority,
    )

    private fun vector(
        x: String = "0",
        y: String = "0",
        z: String = "0",
    ) = AnimationVectorExpression(
        x = AnimationExpression(x),
        y = AnimationExpression(y),
        z = AnimationExpression(z),
    )
}
