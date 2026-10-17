package ru.hollowhorizon.hollowengine.client.models.bedrock

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import ru.hollowhorizon.hollowengine.common.utils.nbt.ListOrSingle
import ru.hollowhorizon.hollowengine.common.utils.nbt.TreeMap


@Serializable
data class BedrockAnimationFile(
    @SerialName("format_version")
    val formatVersion: String,
    val animations: Map<String, Animation> = emptyMap(),
) {
    @Serializable
    data class Animation(
        val loop: Loop = Loop.False,
        @SerialName("animation_length")
        val animationLength: Float? = null,
        val bones: Map<String, Channels> = emptyMap(),
        @SerialName("particle_effects")
        val particleEffects: Map<Float, ListOrSingle<ParticleEffect>> = emptyMap(),
        @SerialName("sound_effects")
        val soundEffects: Map<Float, ListOrSingle<SoundEffect>> = emptyMap(),
    ) {
        @Serializable
        data class ParticleEffect(
            val effect: String,
            val locator: String? = null,
            @SerialName("pre_effect_script")
            val preEffectScript: FloatExpr? = null,
        )

        @Serializable
        data class SoundEffect(
            val effect: String,
            val locator: String? = null,
        )
    }

    @Serializable(with = LoopSerializer::class)
    enum class Loop {
        False,
        True,
        HoldOnLastFrame,
    }
}

internal class LoopSerializer : KSerializer<BedrockAnimationFile.Loop> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): BedrockAnimationFile.Loop =
        when (val json = (decoder as JsonDecoder).decodeJsonElement().jsonPrimitive.content) {
            "false" -> BedrockAnimationFile.Loop.False
            "true" -> BedrockAnimationFile.Loop.True
            "hold_on_last_frame" -> BedrockAnimationFile.Loop.HoldOnLastFrame
            else -> throw IllegalArgumentException("Unexpected value \"$json\"")
        }

    override fun serialize(encoder: Encoder, value: BedrockAnimationFile.Loop) =
        (encoder as JsonEncoder).encodeJsonElement(
            when (value) {
                BedrockAnimationFile.Loop.False -> JsonPrimitive(false)
                BedrockAnimationFile.Loop.True -> JsonPrimitive(true)
                BedrockAnimationFile.Loop.HoldOnLastFrame -> JsonPrimitive("hold_on_last_frame")
            }
        )
}

@Serializable
data class Channels(
    val position: Keyframes? = null,
    val rotation: Keyframes? = null,
    val scale: Keyframes? = null,
    @SerialName("relative_to")
    val relativeTo: RelativeTo = RelativeTo(),
) {
    @Serializable
    data class RelativeTo(
        val rotation: String? = null,
    )
}

@Serializable(with = KeyframesSerializer::class)
data class Keyframes(
    val frames: TreeMap<Float, Keyframe>,
)

@Serializable(with = KeyframeSerializer::class)
data class Keyframe(
    val pre: FloatVec3Expr,
    val post: FloatVec3Expr,
    /** Sections around the keyframe are interpolated using Catmull-Rom splines instead of linear interpolation. */
    val smooth: String,
)

internal class KeyframesSerializer : KSerializer<Keyframes> {
    override val descriptor: SerialDescriptor = InnerSerializer.descriptor
    override fun deserialize(decoder: Decoder): Keyframes = Keyframes(InnerSerializer.deserialize(decoder))
    override fun serialize(encoder: Encoder, value: Keyframes) = InnerSerializer.serialize(encoder, value.frames)

    private object InnerSerializer : JsonTransformingSerializer<TreeMap<Float, Keyframe>>(serializer()) {
        override fun transformDeserialize(element: JsonElement): JsonElement =
            if (element is JsonObject) element else buildJsonObject { put("0", element) }
    }
}

internal object KeyframeSerializer : KSerializer<Keyframe> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor
    override fun deserialize(decoder: Decoder): Keyframe = parse((decoder as JsonDecoder).decodeJsonElement())
    override fun serialize(encoder: Encoder, value: Keyframe) = throw UnsupportedOperationException()

    private fun parse(json: JsonElement): Keyframe = with(json) {
        fun JsonElement.parseVector(): FloatVec3Expr = if (this is JsonArray) {
            if (size == 3) {
                FloatVec3Expr(
                    (get(0) as JsonPrimitive).parseBedrockExpression(),
                    (get(1) as JsonPrimitive).parseBedrockExpression(),
                    (get(2) as JsonPrimitive).parseBedrockExpression()
                )
            } else {
                (get(0) as JsonPrimitive).parseBedrockExpression().let { FloatVec3Expr(it, it, it) }
            }
        } else {
            (this as JsonPrimitive).parseBedrockExpression().let { FloatVec3Expr(it, it, it) }
        }
        if (this is JsonObject) {
            val pre = get("pre")?.parseVector()
            val post = get("post")!!.parseVector()
            val smooth = get("lerp_mode")?.jsonPrimitive?.contentOrNull
            Keyframe(pre ?: post, post, smooth ?: "linear")
        } else {
            parseVector().let { Keyframe(it, it, "linear") }
        }
    }
}