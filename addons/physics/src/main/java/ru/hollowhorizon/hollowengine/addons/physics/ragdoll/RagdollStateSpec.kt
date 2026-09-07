package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.models.AnimationControllerStateSpec
import ru.hollowhorizon.hollowengine.common.models.AnimatorStateType

/**
 * Controller state that moves the model's bones to the physics engine.
 */
@Serializable
@SerialName(RagdollStateSpec.TYPE_ID)
data class RagdollStateSpec(
    override val id: String = "ragdoll",
    /** Simulates only this bone and whatever is attached to it. Null starts from layer's mask. */
    val rootBone: String? = null,
    /** Kilograms per cubic metre of bone. */
    val density: Float = 1050f,
    /** How thick a bone's capsule is, as a fraction of its length. */
    val boneRadiusRatio: Float = 0.28f,
    val minBoneRadius: Float = 0.035f,
    val maxBoneRadius: Float = 0.22f,
    /** How long a bone with nothing below it. (hand/foot/head) */
    val leafBoneLength: Float = 0.12f,
    /** How far a joint may turn around the bone, in degrees. */
    val twistAngle: Float = 30f,
    /** How far a joint may bend away from the bone, in degrees. */
    val swingAngle: Float = 55f,
    val linearDamping: Float = 0.05f,
    val angularDamping: Float = 0.15f,
    val gravityFactor: Float = 1f,
    val friction: Float = 0.6f,
    val restitution: Float = 0f,
    /** How much of the entity's own motion the rag-doll starts with. */
    val inheritVelocity: Float = 1f,
    val collideWithBlocks: Boolean = true,
    /** How far around the rag-doll blocks are given collision, in blocks. */
    // TODO: I guess this can be calculated automatically
    val blockRadius: Int = 3,
    /** Per-bone overrides, by bone name. */
    val bones: Map<String, RagdollBoneSpec> = emptyMap(),
) : AnimationControllerStateSpec() {
    override fun withId(id: String) = copy(id = id)

    fun boneSpec(name: String): RagdollBoneSpec? = bones[name]

    companion object {
        const val TYPE_ID = "hollowengine:physics/ragdoll"

        val TYPE = AnimatorStateType(
            id = TYPE_ID,
            specClass = RagdollStateSpec::class,
            serializer = serializer(),
            titleKey = "hollowengine.gui.animator_editor.state_kind_ragdoll",
            createDefault = { id -> RagdollStateSpec(id = id) },
        )
    }
}

/**
 * What one bone does differently from the rest of the ragdoll.
 */
@Serializable
data class RagdollBoneSpec(
    val simulated: Boolean = true,
    val radius: Float? = null,
    val density: Float? = null,
    val twistAngle: Float? = null,
    val swingAngle: Float? = null,
)
