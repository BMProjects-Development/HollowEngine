package ru.hollowhorizon.hollowengine.client.models.internal.v2

import ru.hollowhorizon.hollowengine.common.scene.SceneTransformParent

/** Uses the owning attachment's cached matrix as a scene's parent transform. */
internal class AttachmentSceneTransform(private val attachment: Attachment) : SceneTransformParent {
    override val matrix get() = attachment.globalMatrix
    override val version get() = attachment.globalVersion
}
