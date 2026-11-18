import kotlinx.coroutines.flow.MutableStateFlow
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Scene
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment

internal fun renderModelNode(definition: NodeDefinition) =
    ModelAttachment(MutableStateFlow(Model(0, listOf(Scene(listOf(definition))), emptySet())), null).nodes.single()
