package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRange
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorWidget
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorWidgets
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

private const val LANG = "hollowengine.gui.rig_editor.ik"

@Serializable
@SerialName(IkTargetSpec.TYPE_ID)
data class IkTargetSpec(
    @EditorName("$LANG.target_name")
    @EditorDescription("$LANG.target_name.hint")
    override val id: String = "target",
    @EditorName("$LANG.offset")
    override val offset: Vec3f = Vec3f.ZERO,
    @EditorName("$LANG.rotation")
    @EditorDescription("$LANG.rotation.hint")
    override val rotation: Vec3f = Vec3f.ZERO,
    @EditorHidden
    override val scale: Float = 1f,
) : RigAttachmentSpec(), PlacedAttachmentSpec {
    override fun withId(id: String) = copy(id = id)

    override fun placedAt(offset: Vec3f, rotation: Vec3f, scale: Float) = copy(offset = offset, rotation = rotation)

    /** A target builds nothing on the drawn model. */
    override fun structure() = IkTargetSpec(id = id)

    companion object {
        const val TYPE_ID = "hollowengine:rig/ik_target"

        val TYPE = RigAttachmentType(
            id = TYPE_ID,
            specClass = IkTargetSpec::class,
            serializer = serializer(),
            titleKey = "hollowengine.gui.rig_editor.kind_ik_target",
            createDefault = { id -> IkTargetSpec(id = id) },
            allowedOnModel = true,
            namedAcrossRig = true,
            editorColor = 0xB57CFF,
        )
    }
}

@Serializable
@SerialName(IkChainSpec.TYPE_ID)
data class IkChainSpec(
    @EditorHidden
    override val id: String = "ik",
    @EditorName("$LANG.bones")
    @EditorDescription("$LANG.bones.hint")
    @EditorRange(min = 1.0, max = MAX_BONES.toDouble())
    val bones: Int = 2,
    @EditorName("$LANG.target")
    @EditorDescription("$LANG.target.hint")
    @EditorWidget(EditorWidgets.RIG_TARGETS)
    val target: String = "",
    @EditorName("$LANG.pole")
    @EditorDescription("$LANG.pole.hint")
    @EditorWidget(EditorWidgets.RIG_TARGETS)
    val pole: String = "",
    @EditorName("$LANG.ground")
    @EditorDescription("$LANG.ground.hint")
    val ground: Boolean = false,
    @EditorName("$LANG.weight")
    @EditorDescription("$LANG.weight.hint")
    @EditorWidget(EditorWidgets.ANIMATION_EXPRESSION)
    val weight: AnimationExpression = AnimationExpression.ONE,
    @EditorName("$LANG.stretch")
    @EditorDescription("$LANG.stretch.hint")
    @EditorRange(min = 1.0, max = 4.0)
    val stretch: Float = 1f,
    @EditorName("$LANG.end")
    @EditorDescription("$LANG.end.hint")
    val end: IkEndRotation = IkEndRotation.KEEP,
) : RigAttachmentSpec() {
    override fun withId(id: String) = copy(id = id)

    override fun structure() = IkChainSpec(id = id)

    companion object {
        const val TYPE_ID = "hollowengine:rig/ik_chain"

        const val MAX_BONES = 8

        val TYPE = RigAttachmentType(
            id = TYPE_ID,
            specClass = IkChainSpec::class,
            serializer = serializer(),
            titleKey = "hollowengine.gui.rig_editor.kind_ik_chain",
            createDefault = { id -> IkChainSpec(id = id) },
            editorColor = 0x4FD1FF,
        )
    }
}

/** How the bone at the end of a chain is turned once the chain has bent. */
@Serializable
enum class IkEndRotation {
    /** As the animation turned it in the model, so a planted foot keeps its angle however the leg bends. */
    @EditorName("$LANG.end.keep")
    KEEP,

    /** As the target is turned, so a hand takes the grip the target describes. */
    @EditorName("$LANG.end.target")
    TARGET,

    /** Along with the bone above it, as if the chain had been bent by hand. */
    @EditorName("$LANG.end.follow")
    FOLLOW,
}

/** Every IK chain of the rig with the bone it hangs on, in the order they are solved. */
fun ModelRig.ikChains(): List<Pair<String, IkChainSpec>> =
    bones.flatMap { (name, bone) -> bone.attachments.filterIsInstance<IkChainSpec>().map { name to it } }

/** The target named [id], with the bone it hangs on, null for the model itself. */
fun ModelRig.ikTarget(id: String): Pair<String?, IkTargetSpec>? =
    allAttachments().firstNotNullOfOrNull { (bone, spec) -> (spec as? IkTargetSpec)?.takeIf { it.id == id }?.let { bone to it } }

/** This rig with the IK target [from] called [to] in every chain that reaches for it or bends toward it. */
fun ModelRig.withIkTargetRenamed(from: String, to: String): ModelRig {
    fun RigBone.renamed() = copy(attachments = attachments.map { spec ->
        if (spec !is IkChainSpec) return@map spec
        spec.copy(target = if (spec.target == from) to else spec.target, pole = if (spec.pole == from) to else spec.pole)
    })
    return copy(bones = bones.mapValues { (_, bone) -> bone.renamed() })
}
