@file:OptIn(ExperimentalSerializationApi::class)

package ru.hollowhorizon.hollowengine.client.particles.file

import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import ru.hollowhorizon.hollowengine.client.models.bedrock.*
import ru.hollowhorizon.hollowengine.common.utils.Color
import ru.hollowhorizon.hollowengine.common.utils.nbt.ListOrSingle
import ru.hollowhorizon.hollowengine.common.utils.nbt.PairAsList
import ru.hollowhorizon.hollowengine.common.utils.nbt.TreeMap

@Serializable
data class BedrockParticleFile(
    @SerialName("format_version")
    val formatVersion: String,
    @SerialName("particle_effect")
    val particleEffect: ParticleEffect,
) {
    @Serializable
    data class ParticleEffect(
        val description: Description,
        val curves: Map<String, Curve> = emptyMap(),
        val events: Map<String, Event> = emptyMap(),
        val components: ParticleComponents = ParticleComponents(),
    )

    @Serializable
    data class Description(
        val identifier: String,
        @SerialName("basic_render_parameters")
        val basicRenderParameters: BasicRenderParameters = BasicRenderParameters(),
    )

    @Serializable
    data class BasicRenderParameters(
        val material: Material = Material.Cutout,
        val texture: String = "texture",
    )

    @Serializable
    enum class Material(val needsSorting: Boolean, val backfaceCulling: Boolean) {
        @SerialName("particles_add")
        Add(true, true),

        @SerialName("particles_alpha")
        Cutout(false, true),

        @SerialName("particles_blend")
        Blend(true, false),
    }

    @Serializable
    data class Curve(
        val type: Type,
        val nodes: List<FloatExpr>,
        val input: FloatExpr,
        @SerialName("horizontal_range")
        val range: FloatExpr = FloatExpr.ONE,
    ) {
        @Serializable
        enum class Type {
            @SerialName("linear")
            Linear,

            @SerialName("bezier")
            Bezier,

            @SerialName("bezier_chain")
            BezierChain,

            @SerialName("catmull_rom")
            CatmullRom
        }
    }

    @Serializable
    data class Event(
        val sequence: List<Event>? = null,
        val randomize: List<@Serializable(with = WeightedEventSerializer::class) RandomizeOption>? = null,
        @SerialName("particle_effect")
        val particle: Particle? = null,
        @SerialName("sound_effect")
        val sound: Sound? = null,
        val expression: FloatExpr? = null,
    ) {
        @Serializable
        data class RandomizeOption(
            val weight: Float,
            val value: Event,
        )

        @Serializable
        data class Particle(
            val effect: String,
            val type: Type,
            @SerialName("pre_effect_expression")
            val preEffectExpression: FloatExpr = FloatExpr.ZERO,
        ) {
            @Serializable
            enum class Type {
                @SerialName("emitter")
                EMITTER,

                @SerialName("emitter_bound")
                EMITTER_BOUND,

                @SerialName("particle")
                PARTICLE,

                @SerialName("particle_with_velocity")
                PARTICLE_WITH_VELOCITY;

                val isParticle: Boolean get() = this == PARTICLE || this == PARTICLE_WITH_VELOCITY
                val inheritVelocity: Boolean
                    get() = this == PARTICLE_WITH_VELOCITY

                val isBound: Boolean
                    get() = this == EMITTER_BOUND
            }
        }

        @Serializable
        data class Sound(
            @SerialName("event_name")
            val eventName: String,
        )

        private class WeightedEventSerializer :
            JsonTransformingSerializer<RandomizeOption>(RandomizeOption.serializer()) {
            override fun transformDeserialize(element: JsonElement): JsonElement = if (element is JsonObject) {
                buildJsonObject {
                    put("weight", element["weight"]!!)
                    put("value", JsonObject(element.filterKeys { it != "weight" }))
                }
            } else element

            override fun transformSerialize(element: JsonElement): JsonElement {
                return JsonObject((element as JsonObject).filterKeys { it != "value" } + element["value"] as JsonObject)
            }
        }
    }
}

@Serializable
data class ParticleComponents(
    @SerialName("minecraft:emitter_local_space")
    val emitterLocalSpace: EmitterLocalSpace? = null,
    @SerialName("minecraft:emitter_initialization")
    val emitterInitialization: EmitterInitialization? = null,
    @SerialName("minecraft:emitter_lifetime_events")
    val emitterLifetimeEvents: EmitterLifetimeEvents = EmitterLifetimeEvents(),
    @SerialName("minecraft:emitter_lifetime_looping")
    val emitterLifetimeLooping: EmitterLifetimeLooping? = null,
    @SerialName("minecraft:emitter_lifetime_once")
    val emitterLifetimeOnce: EmitterLifetimeOnce? = null,
    @SerialName("minecraft:emitter_lifetime_expression")
    val emitterLifetimeExpression: EmitterLifetimeExpression? = null,
    @SerialName("minecraft:emitter_rate_instant")
    val emitterRateInstant: EmitterRateInstant? = null,
    @SerialName("minecraft:emitter_rate_steady")
    val emitterRateSteady: EmitterRateSteady? = null,
    @SerialName("minecraft:emitter_shape_point")
    val emitterShapePoint: EmitterShapePoint? = null,
    @SerialName("minecraft:emitter_shape_sphere")
    val emitterShapeSphere: EmitterShapeSphere? = null,
    @SerialName("minecraft:emitter_shape_box")
    val emitterShapeBox: EmitterShapeBox? = null,
    @SerialName("minecraft:emitter_shape_disc")
    val emitterShapeDisc: EmitterShapeDisc? = null,
    @SerialName("minecraft:particle_lifetime_events")
    val particleLifetimeEvents: ParticleLifetimeEvents = ParticleLifetimeEvents(),
    @SerialName("minecraft:particle_appearance_billboard")
    val particleAppearanceBillboard: ParticleBillboard? = null,
    @SerialName("minecraft:particle_appearance_tinting")
    val particleAppearanceTinting: ParticleAppearanceTinting? = null,
    @SerialName("minecraft:particle_appearance_lighting")
    val particleAppearanceLighting: Unit? = null,
    @SerialName("minecraft:particle_initial_speed")
    val particleInitialSpeed: FloatExpr = FloatExpr.ZERO,
    @SerialName("minecraft:particle_initial_spin")
    val particleInitialSpin: ParticleInitialSpin? = null,
    @SerialName("minecraft:particle_initialization")
    val particleInitialization: ParticleInitialization? = null,
    @SerialName("minecraft:particle_motion_collision")
    val particleMotionCollision: ParticleMotionCollision? = null,
    @SerialName("minecraft:particle_motion_dynamic")
    val particleMotionDynamic: ParticleMotionDynamic? = null,
    @SerialName("minecraft:particle_motion_parametric")
    val particleMotionParametric: ParticleMotionParametric? = null,
    @SerialName("minecraft:particle_lifetime_expression")
    val particleLifetimeExpression: ParticleLifetimeExpression? = null,
    @SerialName("essential:particle_visibility")
    val particleVisibility: ParticleVisibility = ParticleVisibility(),
) {
    @Serializable
    data class EmitterLocalSpace(
        val position: Boolean = false,
        val rotation: Boolean = false,
        val velocity: Boolean = false,
    )

    @Serializable
    data class EmitterInitialization(
        @SerialName("creation_expression")
        val creationExpression: FloatExpr? = null,
        @SerialName("per_update_expression")
        val perUpdateExpression: FloatExpr? = null,
    )


    @Serializable
    data class EmitterLifetimeEvents(
        @SerialName("creation_event")
        val creationEvents: ListOrSingle<String> = emptyList(),
        @SerialName("expiration_event")
        val expirationEvents: ListOrSingle<String> = emptyList(),
        val timeline: TreeMap<Float, ListOrSingle<String>> = TreeMap(emptyMap()),
        @SerialName("travel_distance_events")
        val travelDistanceEvents: TreeMap<Float, ListOrSingle<String>> = TreeMap(emptyMap()),
        @SerialName("looping_travel_distance_events")
        val loopingTravelDistanceEvents: List<LoopingDistance> = emptyList(),
    ) {
        @Serializable
        data class LoopingDistance(
            val distance: Float,
            val effects: List<String>,
        )
    }


    @Serializable
    data class EmitterLifetimeLooping(
        @SerialName("active_time")
        val activeTime: FloatExpr = FloatExpr.literal(10f),
        @SerialName("sleep_time")
        val sleepTime: FloatExpr = FloatExpr.ZERO,
    )


    @Serializable
    data class EmitterLifetimeOnce(
        @SerialName("active_time")
        val activeTime: FloatExpr = FloatExpr.literal(10f),
    )


    @Serializable
    data class EmitterLifetimeExpression(
        @SerialName("activation_expression")
        val activationExpression: FloatExpr = FloatExpr.ONE,
        @SerialName("expiration_expression")
        val expirationExpression: FloatExpr = FloatExpr.ZERO,
    )

    @Serializable
    data class EmitterRateInstant(
        @SerialName("num_particles")
        val numParticles: FloatExpr = FloatExpr.literal(10f),
    )

    @Serializable
    data class EmitterRateSteady(
        @SerialName("spawn_rate")
        val spawnRate: FloatExpr = FloatExpr.ONE,
        @SerialName("max_particles")
        val maxParticles: FloatExpr = FloatExpr.literal(50f),
    )

    @Serializable
    data class EmitterShapePoint(
        val offset: FloatVec3Expr = FloatVec3Expr.ZERO,
        val direction: Direction = Direction.Outwards,
    )

    @Serializable(with = Direction.DirectionSerializer::class)
    sealed class Direction {
        @Serializable(with = InwardsSerializer::class)
        data object Inwards : Direction()

        @Serializable(with = OutwardsSerializer::class)
        data object Outwards : Direction()

        @Serializable(with = CustomSerializer::class)
        data class Custom(val vec: FloatVec3Expr) : Direction()

        object DirectionSerializer : JsonContentPolymorphicSerializer<Direction>(Direction::class) {
            override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Direction> = when {
                element is JsonPrimitive && element.content == "inwards" -> Inwards.serializer()
                element is JsonPrimitive && element.content == "outwards" -> Outwards.serializer()
                else -> Custom.serializer()
            }
        }

        object InwardsSerializer : ObjectAsString<Inwards>("inwards", Inwards)
        object OutwardsSerializer : ObjectAsString<Outwards>("outwards", Outwards)

        object CustomSerializer : KSerializer<Custom> {
            private val inner = FloatVec3ExprSerializer
            override val descriptor: SerialDescriptor = inner.descriptor
            override fun deserialize(decoder: Decoder) = Custom(inner.deserialize(decoder))
            override fun serialize(encoder: Encoder, value: Custom) = inner.serialize(encoder, value.vec)
        }

        open class ObjectAsString<T>(private val str: String, private val value: T) : KSerializer<T> {
            private val inner = String.serializer()
            override val descriptor: SerialDescriptor = inner.descriptor
            override fun deserialize(decoder: Decoder) = inner.deserialize(decoder).let { value }
            override fun serialize(encoder: Encoder, value: T) = inner.serialize(encoder, str)
        }
    }

    @Serializable
    data class EmitterShapeBox(
        val offset: FloatVec3Expr = FloatVec3Expr.ZERO,
        @SerialName("half_dimensions")
        val halfDimensions: FloatVec3Expr,
        @SerialName("surface_only")
        val surfaceOnly: Boolean = false,
        val direction: Direction = Direction.Outwards,
    )

    @Serializable
    data class EmitterShapeDisc(
        @SerialName("plane_normal")
        val planeNormal: FloatVec3Expr = FloatVec3Expr.UNIT_Y,
        val offset: FloatVec3Expr = FloatVec3Expr.ZERO,
        val radius: FloatExpr = FloatExpr.ONE,
        @SerialName("surface_only")
        val surfaceOnly: Boolean = false,
        val direction: Direction = Direction.Outwards,
    )

    @Serializable
    data class EmitterShapeSphere(
        val offset: FloatVec3Expr = FloatVec3Expr.ZERO,
        val radius: FloatExpr = FloatExpr.ONE,
        @SerialName("surface_only")
        val surfaceOnly: Boolean = false,
        val direction: Direction = Direction.Outwards,
    )

    @Serializable
    data class ParticleLifetimeEvents(
        @SerialName("creation_event")
        val creationEvents: ListOrSingle<String> = emptyList(),
        @SerialName("expiration_event")
        val expirationEvents: ListOrSingle<String> = emptyList(),
        val timeline: TreeMap<Float, ListOrSingle<String>> = TreeMap(emptyMap()),
    )

    @Serializable
    data class ParticleBillboard(
        val size: PairAsList<FloatExpr, FloatExpr>,
        @SerialName("facing_camera_mode")
        @JsonNames("face_camera_mode")
        val facingCameraMode: FacingCameraMode,
        val direction: Direction = Direction.FromVelocity(),

        val uv: UV = UV(
            uv = Pair(FloatExpr.ZERO, FloatExpr.ZERO),
            uvSize = Pair(FloatExpr.ONE, FloatExpr.ONE)
        ),
    ) {
        @Serializable
        enum class FacingCameraMode {
            @SerialName("rotate_xyz")
            ROTATE_XYZ,
            @SerialName("rotate_y")
            ROTATE_Y,
            @SerialName("lookat_xyz")
            LOOK_AT_XYZ,
            @SerialName("lookat_y")
            LOOK_AT_Y,
            @SerialName("lookat_direction")
            LOOK_AT_DIRECTION,
            @SerialName("direction_x")
            DIRECTION_X,
            @SerialName("direction_y")
            DIRECTION_Y,
            @SerialName("direction_z")
            DIRECTION_Z,
            @SerialName("emitter_transform_xy")
            EMITTER_TRANSFORM_XY,
            @SerialName("emitter_transform_xz")
            EMITTER_TRANSFORM_XZ,
            @SerialName("emitter_transform_yz")
            EMITTER_TRANSFORM_YZ
        }

        @OptIn(ExperimentalSerializationApi::class)
        @Serializable
        @JsonClassDiscriminator("mode")
        sealed class Direction {

            @Serializable
            @SerialName("derive_from_velocity")
            data class FromVelocity(val minSpeedThreshold: Float = 0.01f) : Direction() {
                @Transient
                val minSpeedThresholdSqr: Float = minSpeedThreshold * minSpeedThreshold
            }


            @Serializable
            @SerialName("custom")
            data class Custom(
                @SerialName("custom_direction")
                val direction: FloatVec3Expr,
            ) : Direction()
        }

        @Serializable
        data class UV(
            @SerialName("texture_width")
            val textureWidth: Int = 1,
            @SerialName("texture_height")
            val textureHeight: Int = 1,
            val uv: PairAsList<FloatExpr, FloatExpr>? = null,
            @SerialName("uv_size")
            val uvSize: PairAsList<FloatExpr, FloatExpr>? = null,

            val flipbook: Flipbook? = null,
        ) {

            @Serializable
            data class Flipbook(
                @SerialName("base_UV")
                val base: PairAsList<FloatExpr, FloatExpr>,
                @SerialName("size_UV")
                val size: PairAsList<Float, Float>,
                @SerialName("step_UV")
                val step: PairAsList<Float, Float>,
                @SerialName("frames_per_second")
                val framePerSecond: Float = 1f,
                @SerialName("max_frame")
                val maxFrame: FloatExpr,
                @SerialName("stretch_to_lifetime")
                val stretchToLifetime: Boolean = false,
                val loop: Boolean = false,
            )
        }
    }

    @Serializable
    data class ParticleAppearanceTinting(val color: MolangColorOrGradient)

    @Serializable
    data class ParticleInitialSpin(
        val rotation: FloatExpr = FloatExpr.ZERO,
        @SerialName("rotation_rate")
        val rotationRate: FloatExpr = FloatExpr.ZERO,
    )

    @Serializable
    data class ParticleInitialization(
        @SerialName("per_render_expression")
        val perRenderExpression: FloatExpr? = null,
    )

    @Serializable
    data class ParticleMotionCollision(
        val enabled: FloatExpr = FloatExpr.ONE,
        @SerialName("collision_drag")
        val collisionDrag: Float = 0f,
        @SerialName("coefficient_of_restitution")
        val coefficientOfRestitution: Float = 0f,
        @SerialName("collision_radius")
        val collisionRadius: Float,
        @SerialName("expire_on_contact")
        val expireOnContact: Boolean = false,
        val events: ListOrSingle<Event> = emptyList(),
    ) {
        @Serializable
        data class Event(
            val event: String,
            @SerialName("min_speed")
            val minSpeed: Float = 2f,
        )
    }

    @Serializable
    data class ParticleMotionDynamic(
        @SerialName("linear_acceleration")
        val linearAcceleration: FloatVec3Expr = FloatVec3Expr.ZERO,
        @SerialName("linear_drag_coefficient")
        val linearDragCoefficient: FloatExpr = FloatExpr.ZERO,
        @SerialName("rotation_acceleration")
        val rotationAcceleration: FloatExpr = FloatExpr.ZERO,
        @SerialName("rotation_drag_coefficient")
        val rotationDragCoefficient: FloatExpr = FloatExpr.ZERO,
    )

    @Serializable
    data class ParticleMotionParametric(
        @SerialName("relative_position")
        val relativePosition: FloatVec3Expr = FloatVec3Expr.ZERO,
        val direction: FloatVec3Expr? = null,
        val rotation: FloatExpr = FloatExpr.ZERO,
    )


    @Serializable
    data class ParticleLifetimeExpression(
        @SerialName("expiration_expression")
        val expirationExpression: FloatExpr = FloatExpr.ZERO,
        @SerialName("max_lifetime")
        val maxLifetime: FloatExpr,
    )

    @Serializable
    data class ParticleVisibility(
        @SerialName("first_person")
        val firstPerson: Boolean = true,
        @SerialName("third_person")
        val thirdPerson: Boolean = true,
    )
}

@Serializable(with = MolangColorOrGradientSerializer::class)
sealed interface MolangColorOrGradient {
    fun eval(context: BedrockContext): Color
}

@Serializable(with = MolangColorSerializer::class)
data class MolangColor(
    val r: FloatExpr,
    val g: FloatExpr,
    val b: FloatExpr,
    val a: FloatExpr,
) : MolangColorOrGradient {
    override fun eval(context: BedrockContext): Color =
        Color(r.eval(context), g.eval(context), b.eval(context), a.eval(context))
}

@Serializable
data class MolangGradient(
    @Serializable(with = MolangGradientMapSerializer::class)
    val gradient: TreeMap<Float, MolangColor>,
    val interpolant: FloatExpr,
) : MolangColorOrGradient {
    override fun eval(context: BedrockContext): Color {
        val alpha = interpolant.eval(context)
        val floor = gradient.floorEntry(alpha)
        val ceil = gradient.ceilingEntry(alpha)

        return when {
            floor == null -> ceil!!.value.eval(context)
            ceil == null -> floor.value.eval(context)
            floor == ceil -> floor.value.eval(context)
            else -> floor.value.eval(context)
                .mix(ceil.value.eval(context), (alpha - floor.key) / (ceil.key - floor.key))
        }
    }
}

object MolangColorOrGradientSerializer :
    JsonContentPolymorphicSerializer<MolangColorOrGradient>(MolangColorOrGradient::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<MolangColorOrGradient> =
        if (element is JsonObject) MolangGradient.serializer() else MolangColor.serializer()
}

object MolangColorSerializer : KSerializer<MolangColor> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder): MolangColor = parse((decoder as JsonDecoder).decodeJsonElement())
    override fun serialize(encoder: Encoder, value: MolangColor) = throw UnsupportedOperationException()

    private fun parse(json: JsonElement): MolangColor = with(json) {
        if (this is JsonArray) {
            MolangColor(
                (get(0) as JsonPrimitive).parseBedrockExpression(),
                (get(1) as JsonPrimitive).parseBedrockExpression(),
                (get(2) as JsonPrimitive).parseBedrockExpression(),
                (getOrNull(3) as JsonPrimitive?)?.parseBedrockExpression() ?: FloatExpr.ONE,
            )
        } else {
            val v = (this as JsonPrimitive).content.substring(1).padStart(8, 'f').toLong(16)
            val r = (v shr 16 and 0xff) / 255f
            val g = (v shr 8 and 0xff) / 255f
            val b = (v and 0xff) / 255f
            val a = (v shr 24 and 0xff) / 255f
            MolangColor(
                FloatExpr.literal(r),
                FloatExpr.literal(g),
                FloatExpr.literal(b),
                FloatExpr.literal(a)
            )
        }
    }
}

object MolangGradientMapSerializer : KSerializer<TreeMap<Float, MolangColor>> {
    private val mapSerializer = TreeMap.serializer(Float.serializer(), MolangColor.serializer())
    private val listSerializer = ListSerializer(MolangColor.serializer())

    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): TreeMap<Float, MolangColor> {
        val input = decoder as JsonDecoder
        val tree = input.decodeJsonElement()

        if (tree is JsonArray) {
            val list = input.json.decodeFromJsonElement(listSerializer, tree)
            return TreeMap(list.withIndex().associate { (index, value) -> index.toFloat() / list.lastIndex to value })
        }

        return input.json.decodeFromJsonElement(mapSerializer, tree)
    }

    override fun serialize(encoder: Encoder, value: TreeMap<Float, MolangColor>) = throw UnsupportedOperationException()
}