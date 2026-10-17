package ru.hollowhorizon.hollowengine.client.models.fbx

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.fbx.TransformationComp
import ru.hollowhorizon.hollowengine.client.models.internal.*
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationData
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationLoader
import ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations.Interpolator
import ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations.Linear
import ru.hollowhorizon.hollowengine.client.models.internal.animations.interpolations.SphericalLinear
import ru.hollowhorizon.hollowengine.common.utils.Color
import ru.hollowhorizon.hollowengine.common.utils.math.*
import java.io.IOException
import kotlin.math.abs
import ru.hollowhorizon.hollowengine.client.models.fbx.TransformationComp as Tc
import ru.hollowhorizon.hollowengine.client.models.internal.Material as InternalMaterial
import ru.hollowhorizon.hollowengine.client.models.internal.Model as InternalModel

enum class TransformationComp {
    Translation, RotationOffset, RotationPivot, PreRotation, Rotation, PostRotation,
    RotationPivotInverse, ScalingOffset, ScalingPivot, Scaling, ScalingPivotInverse, GeometricTranslation,
    GeometricRotation, GeometricScaling;

    val i = ordinal

    /** note: this returns the REAL fbx property names  */
    val nameProperty
        get() = when (this) {
            Translation -> "Lcl Translation"
            Rotation -> "Lcl Rotation"
            Scaling -> "Lcl Scaling"
            else -> toString()
        }

    /** XXX a neat way to solve the never-ending special cases for scaling would be to do everything in log space! */
    val defaultValue
        get() = when (this) {
            Scaling -> Vec3f(1f)
            else -> Vec3f(0f)
        }

    companion object {
        val MAX = entries.size
    }
}

operator fun Array<Mat4f>.get(transf: Tc) = get(transf.i)
operator fun Array<Mat4f>.set(transf: Tc, mat: Mat4f) = set(transf.i, mat)

const val BBSCALE = 100f

fun Document.convert(location: ResourceLocation): InternalModel {
    val isBlockBenchModel = creator.contains("blockbench", ignoreCase = true)
    
    // Сначала конвертируем узлы
    val rawNodes = convertNodes(0L, location)
    
    val nodes = ModelSpace.place(
        rawNodes,
        facesPositiveZ = !isBlockBenchModel,
        unitsPerBlock = if (isBlockBenchModel) BBSCALE else 1f,
    )
    
    val scene = Scene(nodes)

    // Собираем все узлы для анимаций
    val allNodes = mutableListOf<NodeDefinition>()
    fun collectNodes(node: NodeDefinition) {
        allNodes.add(node)
        node.children.forEach { collectNodes(it) }
    }
    nodes.forEach { collectNodes(it) }
    val nodeMap = allNodes.associateBy { it.index }

    // Конвертируем анимации
    val animations = animationStacks().mapNotNull { stack ->
        val intermediateAnim = convertAnimationStackIntermediate(stack)
        if (intermediateAnim != null) {
            AnimationLoader.createAnimation(nodeMap, intermediateAnim)
        } else {
            null
        }
    }

    return InternalModel(0, listOf(scene), setOf(), animations)
}

context(doc: Document)
fun convertAnimationStackIntermediate(st: AnimationStack): ImportedAnimation? {
    val channels = mutableListOf<Channel>()

    // FBX stores time in "FBX time units" where 1 second = 46186158000 FBX time units
    // This is the FBX SDK's definition (FBX_KTIME)
    // Keyframe times are stored as absolute time values in FBX time units, NOT as frame numbers
    // Therefore, frameRate from global settings is NOT needed for time conversion
    val FBX_TIME_UNITS_PER_SECOND = 46186158000.0
    val timeScale = 1.0 / FBX_TIME_UNITS_PER_SECOND // Convert from FBX time units to seconds

    // Проходим по всем слоям анимации
    for (layer in st.layers) {
        // Проходим по всем узлам кривых анимации в слое
        for (curveNode in layer.nodes()) {
            val targetModel = curveNode.targetAsModel
            if (targetModel == null) continue

            val nodeId = targetModel.id.toInt()
            val propertyName = curveNode.prop

            // Маппинг свойств FBX на пути GLTF
            val path = when (propertyName) {
                "Lcl Translation" -> "translation"
                "Lcl Rotation" -> "rotation"
                "Lcl Scaling" -> "scale"
                else -> continue // Пропускаем неизвестные свойства
            }

            // Получаем кривые для этого свойства
            val curves = curveNode.curves()

            // Для трансформаций нужно обработать X, Y, Z компоненты
            when (path) {
                "translation", "scale" -> {
                    val xCurve = curves["d|X"]
                    val yCurve = curves["d|Y"]
                    val zCurve = curves["d|Z"]

                    if (xCurve != null && yCurve != null && zCurve != null) {
                        val channel = createVec3fChannel(nodeId, path, xCurve, yCurve, zCurve, timeScale)
                        if (channel != null) channels.add(channel)
                    }
                }

                "rotation" -> {
                    val xCurve = curves["d|X"]
                    val yCurve = curves["d|Y"]
                    val zCurve = curves["d|Z"]

                    if (xCurve != null && yCurve != null && zCurve != null) {
                        val channel = createQuatChannel(
                            nodeId,
                            xCurve,
                            yCurve,
                            zCurve,
                            timeScale,
                            targetModel.rotationOrder
                        )
                        if (channel != null) channels.add(channel)
                    }
                }
            }
        }
    }

    if (channels.isEmpty()) return null

    // Clean animation name by removing FBX internal prefixes
    val cleanName = st.name
        .removePrefix("AnimStack::")
        .removePrefix("AnimationStack::")

    // Создаем промежуточную анимацию
    return ImportedAnimation(cleanName, channels)
}

fun Document.convertNodes(parentId: Long, location: ResourceLocation): List<NodeDefinition> {
    val connections = getConnectionsByDestinationSequenced(parentId, "Model")

    val nodes = ArrayList<NodeDefinition>()

    for (connection in connections) {
        if (connection.prop.isNotEmpty()) continue

        val `object` = connection.sourceObject
        if (`object` == null) {
            HollowEngine.LOGGER.warn("failed to convert source object for Model link")
            continue
        }

        val model = `object` as? Model

        if (model != null) {
            val nodeTransform = generateTransformationNodeChain(model)
            val node = convertModel(model, nodeTransform, location, this)

            node.children.addAll(convertNodes(model.id, location))

            nodes.add(node)
        }
    }

    return nodes
}

fun generateTransformationNodeChain(model: Model): TrsTransformF {

    val props = model.props
    val rot = model.rotationOrder

    val chain = Array(Tc.MAX) { Mat4f.IDENTITY }

    // generate transformation matrices for all the different transformation components
    val zeroEpsilon = 1e-6f
    var isComplex = false

    props<Vec3f>("PreRotation")?.let {
        if (it.sqrLength() > zeroEpsilon) {
            isComplex = true
            chain[Tc.PreRotation] = getRotationMatrix(rot, it)
        }
    }

    props<Vec3f>("PostRotation")?.let {
        if (it.sqrLength() > zeroEpsilon) {
            isComplex = true
            chain[Tc.PostRotation] = getRotationMatrix(rot, it)
        }
    }

    props<Vec3f>("RotationPivot")?.let {
        if (it.sqrLength() > zeroEpsilon) {
            isComplex = true
            chain[Tc.RotationPivot] = Mat4f.translation(it)
            chain[Tc.RotationPivotInverse] = Mat4f.translation(Vec3f(-it.x, -it.y, -it.z))
        }
    }

    props<Vec3f>("RotationOffset")?.let {
        if (it.sqrLength() > zeroEpsilon) {
            isComplex = true
            chain[Tc.RotationOffset] = Mat4f.translation(it)
        }
    }

    props<Vec3f>("ScalingOffset")?.let {
        if (it.sqrLength() > zeroEpsilon) {
            isComplex = true
            chain[Tc.ScalingOffset] = Mat4f.translation(it)
        }
    }

    props<Vec3f>("ScalingPivot")?.let {
        if (it.sqrLength() > zeroEpsilon) {
            isComplex = true
            chain[Tc.ScalingPivot] = Mat4f.translation(it)
            chain[Tc.ScalingPivotInverse] = Mat4f.translation(Vec3f(-it.x, -it.y, -it.z))
        }
    }

    props<Vec3f>("Lcl Translation")?.let {
        if (it.sqrLength() > zeroEpsilon)
            chain[Tc.Translation] = Mat4f.translation(it)
    }

    props<Vec3f>("Lcl Scaling")?.let {
        if (abs(it.sqrLength() - 1f) > zeroEpsilon)
            chain[Tc.Scaling] = Mat4f.scale(it)
    }

    props<Vec3f>("Lcl Rotation")?.let {
        if (it.sqrLength() > zeroEpsilon)
            chain[Tc.Rotation] = getRotationMatrix(rot, it)
    }

    props<Vec3f>("GeometricScaling")?.let {
        if (abs(it.sqrLength() - 1f) > zeroEpsilon)
            chain[Tc.GeometricScaling] = Mat4f.scale(it)
    }

    props<Vec3f>("GeometricRotation")?.let {
        if (it.sqrLength() > zeroEpsilon)
            chain[Tc.GeometricRotation] = getRotationMatrix(rot, it)
    }

    props<Vec3f>("GeometricTranslation")?.let {
        if (it.sqrLength() > zeroEpsilon)
            chain[Tc.GeometricTranslation] = Mat4f.translation(it)
    }

    /*  isComplex needs to be consistent with needsComplexTransformationChain() or the interplay between this code
        and the animation converter would not be guaranteed.    */
    assert(needsComplexTransformationChain(model) == isComplex)

    return TrsTransformF().apply {
        val transform = MutableMat4f()
        chain.forEach {
            transform *= it
        }
        setMatrix(transform)
    }
}

fun Document.getSkinForModel(model: Model): ru.hollowhorizon.hollowengine.client.models.internal.Skin? {
    // Получаем все соединения от модели к скинам
    val skinConnections = getConnectionsBySourceSequenced(model.id, "Deformer")

    for (conn in skinConnections) {
        val skinObj = conn.destinationObject as? Skin
        if (skinObj != null) {
            return convertSkin(skinObj)
        }
    }
    return null
}

fun convertSkin(fbxSkin: Skin): ru.hollowhorizon.hollowengine.client.models.internal.Skin {
    val jointsIds = mutableListOf<Int>()
    val inverseBindMatrices = mutableListOf<Mat4f>()

    for (cluster in fbxSkin.clusters) {
        cluster.node?.let { model ->
            jointsIds.add(model.id.toInt())
            // В FBX transformLink - это матрица бинда в пространстве сустава
            // Нам нужна обратная матрица бинда (inverse bind matrix)
            val inverseBindMatrix = MutableMat4f(cluster.transformLink)
            inverseBindMatrix.invert()
            inverseBindMatrices.add(inverseBindMatrix)
        }
    }

    return Skin(
        jointsIds,
        inverseBindMatrices.toTypedArray()
    )
}

fun convertModel(model: Model, transform: TrsTransformF, location: ResourceLocation, doc: Document): NodeDefinition {
    val isBlockBenchModel = doc.creator.contains("blockbench", ignoreCase = true)
    val primitives = model.geometry.mapNotNull {
        (it as? MeshGeometry)?.let { convertMesh(it, model, location, doc, isBlockBenchModel) }
    }

    val skin = doc.getSkinForModel(model)

    return NodeDefinition(
        model.id.toInt(),
        name = model.name.substringAfter("::"),
        mutableListOf(),
        transform,
        Mesh(primitives, floatArrayOf()),
        skin
    )
}

fun Document.getSkinDataForMesh(mesh: MeshGeometry, model: Model): Pair<Array<Vec4i>?, Array<Vec4f>?> {
    // Получаем скин для геометрии (skin attached to geometry, not model)
    val fbxSkin = mesh.skin ?: return null to null
    
    // Получаем внутренний скин для модели (для получения joint indices)
    val skin = getSkinForModel(model) ?: return null to null

    // Создаем массивы для joints и weights
    val vertexCount = mesh.vertices.size
    val joints = Array(vertexCount) { Vec4i(0, 0, 0, 0) }
    val weights = Array(vertexCount) { Vec4f(0f, 0f, 0f, 0f) }

    // Для каждого кластера в скине
    fbxSkin.clusters.forEach { cluster ->
        val jointIndex = skin.jointsIds.indexOf(cluster.node?.id?.toInt() ?: -1)
        if (jointIndex != -1) {
            // Для каждой вершины в кластере
            for (i in cluster.indices.indices) {
                val vertexIndex = cluster.indices[i]
                if (vertexIndex < vertexCount) {
                    val weight = cluster.weights[i]

                    // Находим свободный слот в векторе joints/weights
                    val jointsVec = joints[vertexIndex]
                    val weightsVec = weights[vertexIndex]

                    // Ищем первый слот с нулевым весом
                    when {
                        weightsVec.x == 0f -> {
                            joints[vertexIndex] = Vec4i(jointIndex, jointsVec.y, jointsVec.z, jointsVec.w)
                            weights[vertexIndex] = Vec4f(weight, weightsVec.y, weightsVec.z, weightsVec.w)
                        }

                        weightsVec.y == 0f -> {
                            joints[vertexIndex] = Vec4i(jointsVec.x, jointIndex, jointsVec.z, jointsVec.w)
                            weights[vertexIndex] = Vec4f(weightsVec.x, weight, weightsVec.z, weightsVec.w)
                        }

                        weightsVec.z == 0f -> {
                            joints[vertexIndex] = Vec4i(jointsVec.x, jointsVec.y, jointIndex, jointsVec.w)
                            weights[vertexIndex] = Vec4f(weightsVec.x, weightsVec.y, weight, weightsVec.w)
                        }

                        weightsVec.w == 0f -> {
                            joints[vertexIndex] = Vec4i(jointsVec.x, jointsVec.y, jointsVec.z, jointIndex)
                            weights[vertexIndex] = Vec4f(weightsVec.x, weightsVec.y, weightsVec.z, weight)
                        }
                    }
                }
            }
        }
    }

    // Нормализуем веса
    for (i in weights.indices) {
        val w = weights[i]
        val total = w.x + w.y + w.z + w.w
        if (total > 0f) {
            weights[i] = Vec4f(w.x / total, w.y / total, w.z / total, w.w / total)
        }
    }

    return joints to weights
}

fun Document.getMorphTargetsForMesh(mesh: MeshGeometry): List<Map<String, FloatArray>> {
    val morphTargets = mutableListOf<Map<String, FloatArray>>()
    
    // Get the blend shape deformer attached to this mesh
    val blendShape = mesh.blendShape ?: return emptyList()
    
    // Iterate through all blend shape channels
    for (channel in blendShape.channels) {
        // Each channel can have multiple shapes (but typically has one)
        for (shape in channel.shapes) {
            val targetData = mutableMapOf<String, FloatArray>()
            
            // Position deltas
            if (shape.vertices.isNotEmpty()) {
                val positionDeltas = FloatArray(shape.vertices.size * 3)
                shape.vertices.forEachIndexed { i, v ->
                    positionDeltas[i * 3] = v.x
                    positionDeltas[i * 3 + 1] = v.y
                    positionDeltas[i * 3 + 2] = v.z
                }
                targetData["POSITION"] = positionDeltas
            }
            
            // Normal deltas
            if (shape.normals.isNotEmpty()) {
                val normalDeltas = FloatArray(shape.normals.size * 3)
                shape.normals.forEachIndexed { i, n ->
                    normalDeltas[i * 3] = n.x
                    normalDeltas[i * 3 + 1] = n.y
                    normalDeltas[i * 3 + 2] = n.z
                }
                targetData["NORMAL"] = normalDeltas
            }
            
            if (targetData.isNotEmpty()) {
                morphTargets.add(targetData)
            }
        }
    }
    
    return morphTargets
}

fun convertMesh(mesh: MeshGeometry, model: Model, location: ResourceLocation, doc: Document, isBlockBench: Boolean = false): Primitive {
    val (joints, jointWeights) = doc.getSkinDataForMesh(mesh, model)
    val morphTargets = doc.getMorphTargetsForMesh(mesh)
    
    // For BlockBench models, normals need to be scaled by 16 to compensate for the 1/16 model scale
    // This is because normals represent surface orientation and need proper magnitude for lighting
    val normals = if (isBlockBench) {
        mesh.normals.map { Vec3f(it.x * BBSCALE, it.y * BBSCALE, it.z * BBSCALE) }.toTypedArray()
    } else {
        mesh.normals.toTypedArray()
    }

    return Primitive(
        positions = mesh.vertices.toTypedArray(),
        normals = normals,
        texCoords = mesh.getTextureCoords(0).map { Vec2f(it.x, 1f - it.y) }.toTypedArray(),
        tangents = mesh.tangents.map { Vec4f(it.x, it.y, it.z, 1f) }.toTypedArray(),
        joints = joints,
        jointWeights = jointWeights,
        indices = mesh.indices.toIntArray(),
        material = model.materials[mesh.materials[0]].convert(
            location,
            mesh.colors.getOrNull(0)?.getOrNull(0) ?: Vec4f(1f, 1f, 1f, 1f)
        ),
        morphTargets = morphTargets,
        weights = FloatArray(morphTargets.size) { 0f }
    )
}

fun Material.convert(model: ResourceLocation, color: Vec4f): InternalMaterial {
    var diffuseTexture = InternalMaterial.MISSING_TEXTURE
    var normalTexture = InternalMaterial.MISSING_NORMAL
    var specularTexture = InternalMaterial.MISSING_SPECULAR

    // Map FBX texture types to internal texture types
    textures.forEach { (type, texture) ->
        texture.media?.let { media ->
            val textureLocation = model.withPath(
                model.path.substringBefore('.') + '/' + media.name.lowercase()
                    .filter(ResourceLocation::validPathChar) + ".png"
            )

            if (media.content.isNotEmpty()) {
                RenderSystem.recordRenderCall {
                    try {
                        val nativeImage = NativeImage.read(media.content)
                        val dynamicTexture = DynamicTexture(nativeImage)
                        Minecraft.getInstance().textureManager.register(textureLocation, dynamicTexture)
                    } catch (e: IOException) {
                        HollowEngine.LOGGER.error("Invalid texture $textureLocation!")
                    }
                }
            }

            when (type) {
                "DiffuseColor", "DiffuseFactor" -> diffuseTexture = textureLocation
                "NormalMap", "Bump", "Normal" -> normalTexture = textureLocation
                "SpecularColor", "SpecularFactor", "Reflection" -> specularTexture = textureLocation
                "EmissiveColor", "EmissiveFactor" -> {
                    // Handle emissive textures if needed
                }
                // Add more texture type mappings as needed
            }
        }
    }

    // Get material properties from FBX material
    val diffuseColor = props("DiffuseColor", Vec3f(0.8f, 0.8f, 0.8f))
    val diffuseFactor = props("DiffuseFactor", 1.0f)
    val transparencyFactor = props("TransparencyFactor", 0.0f)
    val transparencyColor = props("TransparencyColor", Vec3f(0.0f, 0.0f, 0.0f))

    // Calculate final color with transparency
    val finalColor = Color(
        diffuseColor.x * diffuseFactor,
        diffuseColor.y * diffuseFactor,
        diffuseColor.z * diffuseFactor,
        1.0f - transparencyFactor
    )

    // Determine blend mode based on transparency
    val blend = if (transparencyFactor > 0.01f || color.w < 0.99f) {
        InternalMaterial.Blend.BLEND
    } else {
        InternalMaterial.Blend.OPAQUE
    }

    // Check if material is double-sided (common in FBX)
    val doubleSided = props("DoubleSided", 0) != 0

    return InternalMaterial(
        color = finalColor,
        texture = diffuseTexture,
        normalTexture = normalTexture,
        specularTexture = specularTexture,
        doubleSided = doubleSided,
        blend = blend
    )
}

fun needsComplexTransformationChain(model: Model): Boolean {
    val props = model.props

    val zeroEpsilon = 1e-6f
    TransformationComp.entries.filter {
        it != Tc.Rotation && it != Tc.Scaling && it != Tc.Translation && it != Tc.GeometricScaling
                && it != Tc.GeometricRotation && it != Tc.GeometricTranslation
    }.forEach { comp -> props<Vec3f>(comp.nameProperty)?.let { if (it.sqrLength() > zeroEpsilon) return true } }
    return false
}

fun getRotationMatrix(mode: Model.RotOrder, rotation: Vec3f): Mat4f {
    val out = MutableMat4f()
    if (mode == Model.RotOrder.SphericXYZ) {
        HollowEngine.LOGGER.error("Unsupported RotationMode: SphericXYZ")
        return out
    }

    val angleEpsilon = 1e-6f

    val isId = BooleanArray(3, { true })

    val temp = Array(3) { MutableMat4f() }
    if (abs(rotation.z) > angleEpsilon) {
        temp[2].rotate(rotation.z.deg, Vec3f.Z_AXIS)
        isId[2] = false
    }
    if (abs(rotation.y) > angleEpsilon) {
        temp[1].rotate(rotation.y.deg, Vec3f.Y_AXIS)
        isId[1] = false
    }
    if (abs(rotation.x) > angleEpsilon) {
        temp[0].rotate(rotation.x.deg, Vec3f.X_AXIS)
        isId[0] = false
    }

    val order = IntArray(3) { -1 }

    // note: rotation order is inverted since we're left multiplying as is usual in assimp
    when (mode) {
        Model.RotOrder.EulerXYZ -> {
            order[0] = 2
            order[1] = 1
            order[2] = 0
        }

        Model.RotOrder.EulerXZY -> {
            order[0] = 1
            order[1] = 2
            order[2] = 0
        }

        Model.RotOrder.EulerYZX -> {
            order[0] = 0
            order[1] = 2
            order[2] = 1
        }

        Model.RotOrder.EulerYXZ -> {
            order[0] = 2
            order[1] = 0
            order[2] = 1
        }

        Model.RotOrder.EulerZXY -> {
            order[0] = 1
            order[1] = 0
            order[2] = 2
        }

        Model.RotOrder.EulerZYX -> {
            order[0] = 0
            order[1] = 1
            order[2] = 2
        }

        else -> throw Exception()
    }

    assert(order[0] in 0..2)
    assert(order[1] in 0..2)
    assert(order[2] in 0..2)

    if (!isId[order[0]]) out *= temp[order[0]]
    if (!isId[order[1]]) out *= temp[order[1]]
    if (!isId[order[2]]) out *= temp[order[2]]

    return out
}


fun Document.convertAnimations() {
    animationStacks().forEach { convertAnimationStack(it) }
}

context(doc: Document)
fun convertAnimationStack(st: AnimationStack): AnimationClip? {
    val channels = mutableListOf<Channel>()

    // FBX stores time in "FBX time units" where 1 second = 46186158000 FBX time units
    // This is the FBX SDK's definition (FBX_KTIME)
    val FBX_TIME_UNITS_PER_SECOND = 46186158000.0
    val timeScale = 1.0 / FBX_TIME_UNITS_PER_SECOND // Convert from FBX time units to seconds

    // Note: frameRate from globals is NOT used for keyframe time conversion
    // FBX keyframe times are already in FBX time units, not in frames

    // Проходим по всем слоям анимации
    for (layer in st.layers) {
        var translation: Interpolator<Vec3f>? = null
        var rotation: Interpolator<QuatF>? = null
        var scale: Interpolator<Vec3f>? = null

        for (curveNode in layer.nodes()) {
            val targetModel = curveNode.targetAsModel
            if (targetModel == null) continue

            val nodeId = targetModel.id.toInt()
            val propertyName = curveNode.prop

            // Маппинг свойств FBX на пути GLTF
            val path = when (propertyName) {
                "Lcl Translation" -> "translation"
                "Lcl Rotation" -> "rotation"
                "Lcl Scaling" -> "scale"
                else -> continue // Пропускаем неизвестные свойства
            }

            // Получаем кривые для этого свойства
            val curves = curveNode.curves()

            // Для трансформаций нужно обработать X, Y, Z компоненты
            when (path) {
                "translation" -> {
                    val xCurve = curves["d|X"]
                    val yCurve = curves["d|Y"]
                    val zCurve = curves["d|Z"]

                    if (xCurve != null && yCurve != null && zCurve != null) {
                        translation = createVector3Channel(nodeId, path, xCurve, yCurve, zCurve, timeScale)
                    }
                }

                "rotation" -> {
                    val xCurve = curves["d|X"]
                    val yCurve = curves["d|Y"]
                    val zCurve = curves["d|Z"]

                    if (xCurve != null && yCurve != null && zCurve != null) {
                        rotation = createRotationChannel(nodeId, xCurve, yCurve, zCurve, timeScale, targetModel.rotationOrder)
                    }
                }

                "scale" -> {
                    val xCurve = curves["d|X"]
                    val yCurve = curves["d|Y"]
                    val zCurve = curves["d|Z"]

                    if (xCurve != null && yCurve != null && zCurve != null) {
                        scale  = createVector3Channel(nodeId, path, xCurve, yCurve, zCurve, timeScale)
                    }
                }
            }
        }
        AnimationData(translation, rotation, scale, null)
    }

    if (channels.isEmpty()) return null

    // Создаем промежуточную анимацию
    // Конвертируем в финальный формат (нужен доступ к узлам, который будет позже)
    // Возвращаем null, так как для создания финальной анимации нужны узлы
    return null
}

private fun createVector3Channel(
    nodeId: Int,
    path: String,
    xCurve: AnimationCurve,
    yCurve: AnimationCurve,
    zCurve: AnimationCurve,
    timeScale: Double,
): Interpolator<Vec3f> {
    // Объединяем все ключевые кадры
    val allTimes = mutableSetOf<Long>()
    allTimes.addAll(xCurve.keys)
    allTimes.addAll(yCurve.keys)
    allTimes.addAll(zCurve.keys)

    val sortedTimes = allTimes.sorted()
    val times = sortedTimes.map { (it * timeScale).toFloat() }.toFloatArray()

    // Интерполируем значения для каждого времени
    val values = mutableListOf<Vec3f>()
    for (time in sortedTimes) {
        val x = interpolateCurveValue(xCurve, time)
        val y = interpolateCurveValue(yCurve, time)
        val z = interpolateCurveValue(zCurve, time)

        values.add(Vec3f(x, y, z))
    }

    return Linear(times, values.toTypedArray())
}

private fun createRotationChannel(
    nodeId: Int,
    xCurve: AnimationCurve,
    yCurve: AnimationCurve,
    zCurve: AnimationCurve,
    timeScale: Double,
    rotationOrder: Model.RotOrder,
): Interpolator<QuatF> {
    // Аналогично createVector3Channel, но конвертируем углы Эйлера в кватернионы
    val allTimes = mutableSetOf<Long>()
    allTimes.addAll(xCurve.keys)
    allTimes.addAll(yCurve.keys)
    allTimes.addAll(zCurve.keys)

    val sortedTimes = allTimes.sorted()
    val times = sortedTimes.map { (it * timeScale).toFloat() }.toFloatArray()

    val values = mutableListOf<QuatF>()
    for (time in sortedTimes) {
        val x = interpolateCurveValue(xCurve, time)
        val y = interpolateCurveValue(yCurve, time)
        val z = interpolateCurveValue(zCurve, time)

        values.add(eulerToQuaternion(x, y, z, rotationOrder))
    }

    return SphericalLinear(times, values.toTypedArray())
}

private fun getInterpolationType(curve: AnimationCurve): String {
    // В FBX есть флаги интерполяции, но для простоты используем LINEAR
    // В реальной реализации нужно анализировать curve.flags и curve.attributes
    return "LINEAR"
}

private fun interpolateCurveValue(curve: AnimationCurve, time: Long): Float {
    if (curve.keys.isEmpty()) return 0f
    if (curve.keys.size == 1) return curve.values[0]

    // Находим индексы ключевых кадров для интерполяции
    var idx = 0
    while (idx < curve.keys.size - 1 && curve.keys[idx + 1] < time) {
        idx++
    }

    if (idx >= curve.keys.size - 1) return curve.values.last()
    if (curve.keys[idx] > time) return curve.values.first()

    val t0 = curve.keys[idx]
    val t1 = curve.keys[idx + 1]
    val v0 = curve.values[idx]
    val v1 = curve.values[idx + 1]

    val alpha = (time - t0).toFloat() / (t1 - t0).toFloat()

    // В зависимости от типа интерполяции
    return when (getInterpolationType(curve)) {
        "STEP" -> v0
        "CUBIC" -> {
            // Кубическая интерполяция (Hermite)
            // Для простоты используем линейную
            v0 + (v1 - v0) * alpha
        }

        else -> v0 + (v1 - v0) * alpha // LINEAR по умолчанию
    }
}

private fun createVec3fChannel(
    nodeId: Int,
    path: String,
    xCurve: AnimationCurve,
    yCurve: AnimationCurve,
    zCurve: AnimationCurve,
    timeScale: Double,
): Channel? {
    if (xCurve.keys.isEmpty() || yCurve.keys.isEmpty() || zCurve.keys.isEmpty()) return null

    // Определяем тип интерполяции на основе кривых
    val interpolation = determineInterpolationType(xCurve, yCurve, zCurve)

    // Объединяем все ключевые кадры
    val allTimes = mutableSetOf<Long>()
    allTimes.addAll(xCurve.keys)
    allTimes.addAll(yCurve.keys)
    allTimes.addAll(zCurve.keys)

    val sortedTimes = allTimes.sorted()
    val times = sortedTimes.map { (it * timeScale).toFloat() }

    // Интерполируем значения для каждого времени
    val values = mutableListOf<Vec3f>()
    for (time in sortedTimes) {
        val x = interpolateCurveValue(xCurve, time)
        val y = interpolateCurveValue(yCurve, time)
        val z = interpolateCurveValue(zCurve, time)

        values.add(Vec3f(x, y, z))
    }

    return Channel(
        node = nodeId,
        path = path,
        times = times,
        interpolation = interpolation,
        values = Vec3fChannelData(values.toTypedArray())
    )
}

private fun determineInterpolationType(
    xCurve: AnimationCurve,
    yCurve: AnimationCurve,
    zCurve: AnimationCurve,
): String {
    // Определяем общий тип интерполяции для всех трех кривых
    // Если все кривые имеют одинаковый тип, используем его
    val xType = getInterpolationType(xCurve)
    val yType = getInterpolationType(yCurve)
    val zType = getInterpolationType(zCurve)

    // Если типы разные, используем наиболее распространенный
    return when {
        xType == yType && yType == zType -> xType
        xType == yType -> xType
        yType == zType -> yType
        xType == zType -> xType
        else -> "LINEAR" // По умолчанию
    }
}

private fun createQuatChannel(
    nodeId: Int,
    xCurve: AnimationCurve,
    yCurve: AnimationCurve,
    zCurve: AnimationCurve,
    timeScale: Double,
    rotationOrder: Model.RotOrder,
): Channel? {
    if (xCurve.keys.isEmpty() || yCurve.keys.isEmpty() || zCurve.keys.isEmpty()) return null

    // Определяем тип интерполяции
    val interpolation = determineInterpolationType(xCurve, yCurve, zCurve)

    // Аналогично createVec3fChannel, но конвертируем углы Эйлера в кватернионы
    val allTimes = mutableSetOf<Long>()
    allTimes.addAll(xCurve.keys)
    allTimes.addAll(yCurve.keys)
    allTimes.addAll(zCurve.keys)

    val sortedTimes = allTimes.sorted()
    val times = sortedTimes.map { (it * timeScale).toFloat() }

    val values = mutableListOf<QuatF>()
    for (time in sortedTimes) {
        val x = interpolateCurveValue(xCurve, time)
        val y = interpolateCurveValue(yCurve, time)
        val z = interpolateCurveValue(zCurve, time)

        // Конвертируем углы Эйлера в кватернион
        val quat = eulerToQuaternion(x, y, z, rotationOrder)
        values.add(quat)
    }

    return Channel(
        node = nodeId,
        path = "rotation",
        times = times,
        interpolation = interpolation,
        values = QuatfChannelData(values.toTypedArray())
    )
}

private fun eulerToQuaternion(x: Float, y: Float, z: Float, order: Model.RotOrder): QuatF {
    // Конвертируем градусы в радианы
    val xRad = x.deg
    val yRad = y.deg
    val zRad = z.deg

    // Создаем кватернионы для каждой оси
    val qx = QuatF(xRad, Vec3f.X_AXIS)
    val qy = QuatF(yRad, Vec3f.Y_AXIS)
    val qz = QuatF(zRad, Vec3f.Z_AXIS)

    // Умножаем в правильном порядке
    return when (order) {
        Model.RotOrder.EulerXYZ -> qz * qy * qx
        Model.RotOrder.EulerXZY -> qy * qz * qx
        Model.RotOrder.EulerYZX -> qx * qz * qy
        Model.RotOrder.EulerYXZ -> qz * qx * qy
        Model.RotOrder.EulerZXY -> qy * qx * qz
        Model.RotOrder.EulerZYX -> qx * qy * qz
        else -> qz * qy * qx // По умолчанию ZYX
    }
}
