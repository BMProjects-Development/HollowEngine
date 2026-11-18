package ru.hollowhorizon.hollowengine.client.models

import kotlinx.coroutines.flow.MutableStateFlow
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Scene
import ru.hollowhorizon.hollowengine.client.models.internal.v2.Attachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment

internal fun renderModel(definition: NodeDefinition, parent: Attachment? = null) =
    ModelAttachment(MutableStateFlow(Model(0, listOf(Scene(listOf(definition))), emptySet())), parent)

internal fun renderModelNode(definition: NodeDefinition, parent: Attachment?) = renderModel(definition, parent).nodes.single()
