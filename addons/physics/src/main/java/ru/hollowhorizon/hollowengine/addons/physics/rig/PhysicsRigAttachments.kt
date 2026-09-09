package ru.hollowhorizon.hollowengine.addons.physics.rig

import ru.hollowhorizon.hollowengine.client.models.internal.v2.Attachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode

/**
 * Body, which the rig fits over the bone.
 */
class RigidBodyAttachment(val spec: RigidBodyAttachmentSpec, val bone: RuntimeNode) : Attachment(bone)

/** What rig says holds [bone]'s body to another one; see [JointAttachmentSpec]. */
class JointAttachment(val spec: JointAttachmentSpec, val bone: RuntimeNode) : Attachment(bone)

fun RuntimeNode.rigidBody(): RigidBodyAttachment? = attachments.firstNotNullOfOrNull { it as? RigidBodyAttachment }
fun RuntimeNode.joint(): JointAttachment? = attachments.firstNotNullOfOrNull { it as? JointAttachment }
