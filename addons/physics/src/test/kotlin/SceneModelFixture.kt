import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.ModelSceneInstance
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Scene as ModelScene
import ru.hollowhorizon.hollowengine.common.events.LogicalSide
import ru.hollowhorizon.hollowengine.common.scene.Scene
import ru.hollowhorizon.hollowengine.common.scene.SceneContext

internal fun sceneModel(definition: NodeDefinition): ModelSceneInstance {
    val scene = Scene(object : SceneContext { override val side = LogicalSide.SERVER })
    val model = Model(0, listOf(ModelScene(listOf(definition))), emptySet())
    return ModelSceneInstance.create(scene.create("owner"), "model", model)
}
