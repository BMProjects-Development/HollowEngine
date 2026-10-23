package ru.hollowhorizon.hollowengine.client.models.fbx

import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.models.internal.Mesh
import ru.hollowhorizon.hollowengine.client.models.internal.ModelSpace
import ru.hollowhorizon.hollowengine.client.models.internal.NodeDefinition
import ru.hollowhorizon.hollowengine.client.models.internal.Scene
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationLoader
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import ru.hollowhorizon.hollowengine.client.models.internal.Material as InternalMaterial
import ru.hollowhorizon.hollowengine.client.models.internal.Model as InternalModel

fun Document.convert(
    location: ResourceLocation,
    side: ModelSide = ModelSide.CLIENT,
    options: FbxImportOptions = FbxImportOptions(),
): InternalModel = FbxConverter(this, location, side, options).convert()

internal class FbxConverter(
    val document: Document,
    val location: ResourceLocation,
    val side: ModelSide,
    private val options: FbxImportOptions,
) {
    val models = document.objects.values.filter { it.element.keyToken.stringContents == "Model" && it.id != 0L }
        .associate { it.id to (it.get<Model>() ?: domError("invalid model", it.element)) }
    val nodeIds = models.keys.withIndex().associate { it.value to it.index }
    private var nextIndex = nodeIds.size
    val morphMeshes = mutableListOf<Pair<Int, MeshGeometry>>()
    val animationTargets = mutableMapOf<Long, Map<String, Int>>()
    private val converted = mutableSetOf<Long>()
    private val active = mutableSetOf<Long>()
    private val materialCache = mutableMapOf<Long, InternalMaterial>()

    fun allocateIndex() = nextIndex++

    fun convert(): InternalModel {
        validateTransformInheritance(document, models.values)
        val roots = convertChildren(0L).toMutableList()
        models.values.filter { model ->
            model.id !in converted && document.getConnectionsBySourceSequenced(model.id, "Model")
                .none { it.prop.isEmpty() }
        }.forEach { model ->
            document.warn(model.name, "model has no valid parent; attaching it to the scene root")
            roots += convertModel(model)
        }
        if (converted.size != models.size) domError("model hierarchy contains a cycle or nodes without a root: ${models.keys - converted}")
        val settings = document.globals ?: domError("missing global settings")
        val axes = listOf(settings.coordAxis, settings.upAxis, settings.frontAxis)
        val signs = listOf(settings.coordAxisSign, settings.upAxisSign, settings.frontAxisSign)
        if (axes.toSet() != setOf(0, 1, 2) || signs.any { it != -1 && it != 1 }) domError("invalid coordinate axes")
        val unit = settings.unitScaleFactor
        if (!unit.isFinite() || unit <= 0f) domError("invalid unit scale factor")
        val basis = FloatArray(16)
        axes.forEachIndexed { row, axis -> basis[axis * 4 + row] = signs[row].toFloat() }
        basis[15] = 1f
        val axisNode = NodeDefinition(
            allocateIndex(),
            null,
            roots.toMutableList(),
            TrsTransformF().setMatrix(Mat4f.fromArray(basis))
        )
        roots.forEach { it.parent = axisNode }
        val nodes = ModelSpace.place(
            listOf(axisNode),
            facesPositiveZ = !document.creator.contains("blockbench", true),
            unitsPerBlock = 100f / unit,
        )
        val allNodes = nodes.flatMap { it.allBones() }.associateBy { it.index }
        val animations = document.animationStacks().mapNotNull { stack ->
            try {
                convertAnimationStack(stack, this)?.let { AnimationLoader.createAnimation(allNodes, it) }
            } catch (ex: FbxImportException) {
                if (options.invalidAnimationPolicy == InvalidAnimationPolicy.REJECT) throw ex
                document.warn(stack.name, "skipping invalid animation: ${ex.message}")
                null
            }
        }
        val materials = allNodes.values.flatMap { it.mesh?.primitives.orEmpty() }.map { it.material }.toSet()
        return InternalModel(0, listOf(Scene(nodes)), materials, animations)
    }

    private fun convertChildren(parent: Long): List<NodeDefinition> =
        document.getConnectionsByDestinationSequenced(parent, "Model").filter { it.prop.isEmpty() }.map { connection ->
            convertModel(models[connection.src] ?: domError("missing model ${connection.src}"))
        }

    private fun convertModel(model: Model): NodeDefinition {
        if (!active.add(model.id)) domError("cycle in model hierarchy", model.element)
        if (!converted.add(model.id)) domError("model has multiple parents", model.element)
        val chain = buildTransformChain(model, nodeIds.getValue(model.id), ::allocateIndex)
        animationTargets[model.id] = chain.targets
        val children = chain.last.children
        model.geometry.filterIsInstance<MeshGeometry>().forEach { mesh ->
            val geometryIndex = allocateIndex()
            val geometryTransform = geometricTransform(model)
            val skin = mesh.skin?.let { convertSkin(it, nodeIds, geometryIndex, geometryTransform.matrixF) }
            val primitives = convertMesh(mesh, model, skin) { material ->
                materialCache.getOrPut(material.id) { material.convert(location, side) }
            }
            val weights = morphWeights(mesh)
            if (weights.isNotEmpty()) morphMeshes += geometryIndex to mesh
            children += NodeDefinition(
                geometryIndex, mesh.name.substringAfter("::"), mutableListOf(), geometryTransform,
                Mesh(primitives, weights), skin,
            )
        }
        children += convertChildren(model.id)
        children.forEach { it.parent = chain.last }
        active.remove(model.id)
        return chain.first
    }
}
