package ru.hollowhorizon.hollowengine.addons.physics.rig

import ru.hollowhorizon.hollowengine.client.models.internal.v2.BaseAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RenderModelNode

/**
 * Body, which the rig fits over the bone.
 */
class RigidBodyAttachment(val spec: RigidBodyAttachmentSpec, val bone: RenderModelNode) : BaseAttachment(bone)

/** What rig says holds [bone]'s body to another one; see [JointAttachmentSpec]. */
class JointAttachment(val spec: JointAttachmentSpec, val bone: RenderModelNode) : BaseAttachment(bone)

fun RenderModelNode.rigidBody(): RigidBodyAttachment? = attachments.firstNotNullOfOrNull { it as? RigidBodyAttachment }
fun RenderModelNode.joint(): JointAttachment? = attachments.firstNotNullOfOrNull { it as? JointAttachment }
