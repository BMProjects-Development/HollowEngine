package ru.hollowhorizon.hollowengine.addons.lighting.client

import net.minecraft.world.entity.Entity
import org.joml.Matrix4f
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.addons.lighting.LightInstance
import ru.hollowhorizon.hollowengine.client.models.internal.v2.Attachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RespecAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.render.RenderPasses
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix4f
import ru.hollowhorizon.hollowengine.addons.lighting.LightAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import java.util.*
import kotlin.math.abs

internal class LightAttachment(spec: LightAttachmentSpec, private val node: RuntimeNode) : Attachment(node),
    RespecAttachment {
    private val key = "attachment/${UUID.randomUUID()}"
    override var spec = spec
        private set
    override val followsWorldPose: Boolean get() = true

    override fun respec(spec: RigAttachmentSpec) {
        if (spec is LightAttachmentSpec) this.spec = spec
    }

    override fun updateWorldPose(modelToWorld: TrsTransformF, entity: Entity) {
        if (!node.isVisible || !spec.light.enabled || RenderPasses.isShadowPass) return
        val placement = Matrix4f(modelToWorld.matrixF.asMatrix4f()).mul(globalMatrix.asMatrix4f())
            .mul(spec.localTransform().matrixF.asMatrix4f())
        val position = placement.getTranslation(Vector3f())
        val direction = placement.transformDirection(Vector3f(0f, 0f, -1f)).normalize()
        val scale = placement.getScale(Vector3f())
        LightingClient.submitAttachment(
            LightInstance(
                key,
                spec.light,
                position.x.toDouble(),
                position.y.toDouble(),
                position.z.toDouble(),
                direction.x,
                direction.y,
                direction.z,
                maxOf(abs(scale.x), abs(scale.y), abs(scale.z))
            ),
            entity,
        )
    }
}
