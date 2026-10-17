package ru.hollowhorizon.hollowengine.client.models.gltf

import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.internal.*
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationLoader
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelLoader
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.client.utils.exists
import ru.hollowhorizon.hollowengine.common.models.ModelResourceIO
import ru.hollowhorizon.hollowengine.common.utils.math.*


object GltfModelLoader : ModelLoader {
    override val supportedFormats = setOf("gltf", "glb")

    private val supportedExtensions = setOf("KHR_texture_transform")

    override suspend fun load(location: ResourceLocation, side: ModelSide): Model {
        val resolvedLocation = if (!location.exists(side)) ModelLoader.FALLBACK_MODEL else location

        val gltf = loadGltf(resolvedLocation, side)
        return load(gltf.getOrThrow(), resolvedLocation, side)
    }

    fun load(file: GltfFile, location: ResourceLocation, side: ModelSide): Model {
        warnUnsupportedExtensions(file, location)
        val skins = parseSkins(file)
        val materials = if (side == ModelSide.SERVER) emptyList() else {
            file.materials.mapIndexed { index, material -> material.toMaterial(file, location, index) }
        }

        // BlockBench exports facing the way a vanilla model does; every other exporter puts the front at +Z.
        val fromBlockBench = file.asset.generator?.contains("blockbench", ignoreCase = true) == true
        val scenes = parseScenes(file, skins, materials, side).mapIndexed { index, scene ->
            Scene(
                ModelSpace.place(
                    scene.nodes,
                    facesPositiveZ = !fromBlockBench,
                    index = ModelSpace.ROOT_INDEX - index,
                )
            )
        }

        val nodes = mutableListOf<NodeDefinition>()

        fun walkNodes(current: NodeDefinition) {
            nodes.add(current)
            for (definition in current.children) {
                walkNodes(definition)
            }
        }

        for (scene in scenes) {
            for (definition in scene.nodes) {
                walkNodes(definition)
            }
        }

        val animations: List<AnimationClip> = parseAnimations(file).map {
            AnimationLoader.createAnimation(nodes.associateBy { it.index }, it)
        }

        val model = Model(file.scene, scenes, materials.toSet(), animations).apply {
            walkNodes().forEach { node ->
                node.skin?.let { skin ->
                    node.mesh?.primitives?.forEach {
                        it.jointCount = skin.jointsIds.size
                    }
                }
            }
        }


        return model
    }


    private fun parseSkins(file: GltfFile): List<Skin> {
        return file.skins.map { skin ->
            return@map Skin(
                skin.joints, Mat4fAccessor(skin.inverseBindMatrixAccessorRef!!).list
            )
        }
    }


    private fun parseScenes(
        file: GltfFile,
        skins: List<Skin>,
        materials: List<Material>,
        side: ModelSide,
    ): List<Scene> {
        return file.scenes.map { scene ->
            val nodes = scene.nodes
            val parsedNodes = nodes.map { parseNode(file, it, file.nodes[it], skins, materials, side) }

            Scene(parsedNodes)
        }
    }

    private fun parseNode(
        file: GltfFile,
        nodeIndex: Int,
        node: GltfNode,
        skins: List<Skin>,
        materials: List<Material>,
        side: ModelSide,
    ): NodeDefinition {

        val children = node.children.map { parseNode(file, it, file.nodes[it], skins, materials, side) }
        val mesh = node.meshRef?.let { mesh ->
            val primitives = mesh.primitives.map { prim ->
                val attributes = prim.attributes.map { it.key to file.accessors[it.value] }.toMap()
                val positions = attributes[GltfMesh.Primitive.ATTRIBUTE_POSITION]?.let { Vec3fAccessor(it) }?.list
                val normals = attributes[GltfMesh.Primitive.ATTRIBUTE_NORMAL]?.let { Vec3fAccessor(it) }?.list
                val uvTransform = file.materials.getOrNull(prim.material)?.uvTransform()
                val texCoord0 =
                    attributes[GltfMesh.Primitive.ATTRIBUTE_TEXCOORD_0]?.let { Vec2fAccessor(it) }?.list?.transformedBy(
                            uvTransform,
                            texCoord = 0
                        )
                val texCoord1 =
                    attributes[GltfMesh.Primitive.ATTRIBUTE_TEXCOORD_1]?.let { Vec2fAccessor(it) }?.list?.transformedBy(
                            uvTransform,
                            texCoord = 1
                        )
                val tangents = attributes[GltfMesh.Primitive.ATTRIBUTE_TANGENT]?.let { Vec4fAccessor(it) }?.list
                val joints = attributes[GltfMesh.Primitive.ATTRIBUTE_JOINTS_0]?.let { Vec4iAccessor(it) }?.list
                val weights = attributes[GltfMesh.Primitive.ATTRIBUTE_WEIGHTS_0]?.let { Vec4fAccessor(it) }?.list

                Primitive(
                    positions,
                    normals,
                    texCoord0,
                    texCoord1,
                    tangents,
                    joints,
                    weights,
                    if (prim.indices != -1) IntAccessor(file.accessors[prim.indices]).list.toIntArray() else null,
                    if (side == ModelSide.CLIENT && prim.material != -1) materials[prim.material] else Material(),
                    prim.targets.map { map ->
                        map.map { entry ->
                            entry.key to file.accessors[entry.value].let { accessor ->
                                when (entry.key) {
                                    GltfMesh.Primitive.ATTRIBUTE_POSITION, GltfMesh.Primitive.ATTRIBUTE_NORMAL -> {
                                        Vec3fAccessor(accessor).list.flatMap { listOf(it.x, it.y, it.z) }.toFloatArray()
                                    }

                                    GltfMesh.Primitive.ATTRIBUTE_TANGENT -> {
                                        Vec3fAccessor(accessor).list.flatMap { listOf(it.x, it.y, it.z) }.toFloatArray()
                                    }

                                    else -> throw IllegalStateException("Unsupported morph target!")
                                }
                            }
                        }.toMap()
                    },
                    (node.weights ?: mesh.weights).toFloatArray().takeIf { it.isNotEmpty() }
                        ?: FloatArray(prim.targets.size) { 0f })
            }

            return@let Mesh(
                primitives,
                mesh.weights.toFloatArray().takeIf { it.isNotEmpty() } ?: primitives.firstOrNull()?.weights?.copyOf()
                ?: FloatArray(0))
        }
        val skin = if (node.skin != -1) skins[node.skin] else null

        val transform = TrsTransformF()
        node.matrix?.let {
            transform.setMatrix(
                Mat4f(
                    it[0], it[1], it[2], it[3],
                    it[4], it[5], it[6], it[7],
                    it[8], it[9], it[10], it[11],
                    it[12], it[13], it[14], it[15],
                ).transpose(MutableMat4f())
            )
        }
        transform.translate(node.translation?.let { Vec3f(it[0], it[1], it[2]) } ?: MutableVec3f())
        transform.rotate(node.rotation?.let { QuatF(it[0], it[1], it[2], it[3]) } ?: MutableQuatF())
        transform.scale(node.scale?.let { Vec3f(it[0], it[1], it[2]) } ?: MutableVec3f(1f, 1f, 1f))

        return NodeDefinition(nodeIndex, node.name, children.toMutableList(), transform, mesh, skin).apply {
            this.children.forEach { it.parent = this }
        }


    }

    @Suppress("UNCHECKED_CAST")
    private fun parseChannel(
        file: GltfFile, channel: GltfAnimation.Channel, samplers: List<GltfAnimation.Sampler>,
    ): Channel {
        val accessors = file.accessors
        val sampler = samplers[channel.sampler]
        val timeValues = FloatAccessor(accessors[sampler.input]).list

        return Channel(
            node = channel.target.node,
            path = channel.target.path,
            times = timeValues.toList(),
            interpolation = sampler.interpolation,
            values = GltfChannelData(accessors[sampler.output])
        )
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun parseAnimations(file: GltfFile): List<ImportedAnimation> {
        return file.animations.filter { it.channels != null }.map { animation ->
            val channels =
                animation.channels.filter { it.target.node != -1 } // Некоторые экспортеры почему-то считают, что экспортировать анимацию без объекта - хорошая идея
                    .map { parseChannel(file, it, animation.samplers) }
            ImportedAnimation(animation.name, channels)
        }
    }

    private fun Array<Vec2f>.transformedBy(uvTransform: BakedUvTransform?, texCoord: Int): Array<Vec2f> {
        if (uvTransform == null || uvTransform.texCoord != texCoord) return this
        return Array(size) { index -> uvTransform.transform.apply(this[index].x, this[index].y) }
    }

    private fun warnUnsupportedExtensions(file: GltfFile, location: ResourceLocation) {
        val missing = file.extensionsRequired.filterNot { it in supportedExtensions }
        if (missing.isNotEmpty()) {
            HollowEngine.LOGGER.warn("Model $location requires glTF extensions the engine ignores: ${missing.joinToString()}")
        }
    }

    private fun ResourceLocation.exists(side: ModelSide): Boolean = when (side) {
        ModelSide.CLIENT -> exists()
        ModelSide.SERVER -> ModelResourceIO.exists(this)
    }

}
