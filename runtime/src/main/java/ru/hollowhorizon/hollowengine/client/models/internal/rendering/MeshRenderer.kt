package ru.hollowhorizon.hollowengine.client.models.internal.rendering

import ru.hollowhorizon.hollowengine.client.models.internal.v2.MeshAttachment

interface MeshRenderer {
    fun init()
    fun setupPipeline(
        pipeline: RenderPipeline,
        instance: MeshAttachment,
    )
    fun destroy()
}
