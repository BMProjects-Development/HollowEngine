package ru.hollowhorizon.hollowengine.client.models.bedrock

import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.internal.*
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationData
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelLoader
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.client.utils.exists
import ru.hollowhorizon.hollowengine.client.utils.stream
import ru.hollowhorizon.hollowengine.common.models.ModelResourceIO
import ru.hollowhorizon.hollowengine.common.utils.json.JsonFormat
import ru.hollowhorizon.hollowengine.common.utils.math.*
import ru.hollowhorizon.hollowengine.common.utils.rl

object BedrockModelLoader : ModelLoader {
    override val supportedFormats = setOf("geo.json")

    private var index = 0

    override suspend fun load(location: ResourceLocation, side: ModelSide): Model {
        val modelData = location.open(side).use { JsonFormat.decodeFromStream<BedrockFile>(it) }
        val parsedModel = convertGeometry(modelData, location, side)

        val animLocation = location.withPath(location.path.substringBefore('.') + ".animation.json")
        val animations = try {
            if (animLocation.exists(side)) {
                val animFile = BedrockExpressions.batch {
                    animLocation.open(side).use { JsonFormat.decodeFromStream<BedrockAnimationFile>(it) }
                }
                convertAnimations(animFile, parsedModel)
            } else emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }

        val modelWithAnim = parsedModel.copy(animations = animations)
        index = 0
        return modelWithAnim
    }

    private fun convertGeometry(file: BedrockFile, location: ResourceLocation, side: ModelSide): Model {
        val scenes = file.geometries.map { geometry ->
            Scene(
                listOf(
                    NodeDefinition(
                        index++,
                        children = geometry.convertNodes(location, side).toMutableList(),
                        transform = TrsTransformF().scale(Vec3f(1 / 16f, 1 / 16f, 1 / 16f))
                    )
                )
            )
        }
        return Model(0, scenes, emptySet(), emptyList())
    }

    private fun BedrockFile.Geometry.convertNodes(location: ResourceLocation, side: ModelSide): List<NodeDefinition> {
        val material = Material(
            name = "texture",
            color = description.color,
            texture = location.withPath(location.path.removeSuffix("geo.json") + "png")
                ?.takeIf { it.exists(side) } ?: description.texture.rl,
            blend = if (description.textureTranslucent) Material.Blend.BLEND else Material.Blend.OPAQUE,
            doubleSided = true
        )

        val rootBones = bones.filter { it.parent == null }

        return rootBones.map { bone ->
            convertNodeRecursive(bone, Vec3f.ZERO, material, bones)
        }
    }

    context(geometry: BedrockFile.Geometry)
    private fun convertNodeRecursive(
        bone: BedrockFile.Bone,
        parentPivot: Vec3f,
        material: Material,
        allBones: List<BedrockFile.Bone>,
    ): NodeDefinition {
        val transform = TrsTransformF()

        val translation = bone.pivot - parentPivot
        transform.translate(BedrockCoordinates.position(translation))

        if (bone.rotation != Vec3f.ZERO) {
            transform.rotate(BedrockCoordinates.rotation(bone.rotation))
        }

        val primitives = bone.cubes.map { cube ->

            val meshData = cube.toMeshData(
                bone.pivot,
                geometry.description.textureWidth,
                geometry.description.textureHeight
            )

            Primitive(
                positions = meshData.vertices.toTypedArray(),
                normals = meshData.normals.toTypedArray(),
                texCoords = meshData.uvs.toTypedArray(),
                indices = meshData.indices.toIntArray(),
                material = material
            )
        }
        val nodeMesh = if (primitives.isNotEmpty()) Mesh(primitives, floatArrayOf()) else null

        val children = allBones.filter { it.parent == bone.name }
            .map { child -> convertNodeRecursive(child, bone.pivot, material, allBones) }
            .toMutableList()

        val nodeDef = NodeDefinition(
            index = index++,
            name = bone.name,
            children = children,
            transform = transform,
            mesh = nodeMesh,
            skin = null
        )

        children.forEach { it.parent = nodeDef }
        return nodeDef
    }

    data class MeshData(
        val vertices: List<Vec3f>,
        val normals: List<Vec3f>,
        val uvs: List<Vec2f>,
        val indices: List<Int>,
    )

    fun BedrockFile.Cube.toMeshData(bonePivot: Vec3f, textureWidth: Int, textureHeight: Int): MeshData {
        val vertices = mutableListOf<Vec3f>()
        val normals = mutableListOf<Vec3f>()
        val uvsOut = mutableListOf<Vec2f>()
        val indices = mutableListOf<Int>()

        val (ox, oy, oz) = origin - bonePivot
        val (sx, sy, sz) = size
        val inf = inflate

        // Keep the Bedrock corner/UV association while reflecting positions into engine space.
        val x0 = -(ox - inf)
        val y0 = oy - inf
        val z0 = oz - inf
        val x1 = -(ox + sx + inf)
        val y1 = oy + sy + inf
        val z1 = oz + sz + inf
        
        val rotMat = rotation.takeIf { it != Vec3f.ZERO }
            ?.let { MutableMat3f().rotate(BedrockCoordinates.rotation(it)) }
        val cubePivotLocal = BedrockCoordinates.position(pivot - bonePivot)

        fun place(v: Vec3f): Vec3f {
            if (rotMat == null) return v
            val local = MutableVec3f(v.x - cubePivotLocal.x, v.y - cubePivotLocal.y, v.z - cubePivotLocal.z)
            rotMat.transform(local)
            return Vec3f(local.x + cubePivotLocal.x, local.y + cubePivotLocal.y, local.z + cubePivotLocal.z)
        }

        fun turn(n: Vec3f): Vec3f = if (rotMat == null) n else rotMat.transform(MutableVec3f(n.x, n.y, n.z))

        val p = arrayOf(
            place(Vec3f(x0, y0, z0)), place(Vec3f(x1, y0, z0)), place(Vec3f(x1, y1, z0)), place(Vec3f(x0, y1, z0)), // front
            place(Vec3f(x0, y0, z1)), place(Vec3f(x1, y0, z1)), place(Vec3f(x1, y1, z1)), place(Vec3f(x0, y1, z1))  // back
        )
        val faces = listOf(
            listOf(0, 1, 2, 3) to Vec3f(0f, 0f, -16f) to "north",
            listOf(5, 4, 7, 6) to Vec3f(0f, 0f, 16f) to "south",
            listOf(4, 0, 3, 7) to Vec3f(16f, 0f, 0f) to "west",
            listOf(1, 5, 6, 2) to Vec3f(-16f, 0f, 0f) to "east",
            listOf(3, 2, 6, 7) to Vec3f(0f, 16f, 0f) to "up",
            listOf(4, 5, 1, 0) to Vec3f(0f, -16f, 0f) to "down"
        )

        val uvMap: Map<String, BedrockFile.UvFace?> = when (uv) {
            is BedrockFile.Uvs.PerFace -> mapOf(
                "north" to uv.north, "south" to uv.south,
                "west" to uv.west, "east" to uv.east,
                "up" to uv.up, "down" to uv.down
            )

            is BedrockFile.Uvs.Box -> generateBoxUVs(uv.uv, size)
        }

        for ((faceWithNormal, name) in faces) {
            val (face, normal) = faceWithNormal
            val start = vertices.size
            val uvFace = uvMap[name]

            val uvCoords = if (uvFace != null) {
                val (u, v) = uvFace.uv
                val (w, h) = uvFace.size
                listOf(
                    Vec2f(u, v + h),
                    Vec2f(u + w, v + h),
                    Vec2f(u + w, v),
                    Vec2f(u, v)
                )
            } else {
                listOf(
                    Vec2f(0f, 1f),
                    Vec2f(1f, 1f),
                    Vec2f(1f, 0f),
                    Vec2f(0f, 0f)
                )
            }

            val indicesForFace = listOf(0, 1, 2, 2, 3, 0)

            val faceNormal = turn(normal)

            for (i in 0..3) {
                vertices += p[face[i]]
                normals += faceNormal
                uvsOut += if (mirror == true && (name == "west" || name == "east")) {
                    Vec2f(1f - uvCoords[i].x / textureWidth, uvCoords[i].y / textureHeight)
                } else uvCoords[i] / Vec2f(textureWidth.toFloat(), textureHeight.toFloat())
            }

            indices += indicesForFace.map { start + it }
        }
        return MeshData(vertices, normals, uvsOut, indices)
    }

    fun generateBoxUVs(boxUv: FloatArray, size: Vec3f): Map<String, BedrockFile.UvFace> {
        val (u, v) = boxUv
        val w = kotlin.math.abs(size.x)
        val h = kotlin.math.abs(size.y)
        val d = kotlin.math.abs(size.z)

        return mapOf(
            "up" to BedrockFile.UvFace(floatArrayOf(u + d, v), floatArrayOf(w, d)),
            "down" to BedrockFile.UvFace(floatArrayOf(u + d + w, v), floatArrayOf(w, d)),
            "east" to BedrockFile.UvFace(floatArrayOf(u, v + d), floatArrayOf(d, h)),
            "north" to BedrockFile.UvFace(floatArrayOf(u + d, v + d), floatArrayOf(w, h)),
            "west" to BedrockFile.UvFace(floatArrayOf(u + d + w, v + d), floatArrayOf(d, h)),
            "south" to BedrockFile.UvFace(floatArrayOf(u + d + w + d, v + d), floatArrayOf(w, h)),
        )
    }

    private fun convertAnimations(file: BedrockAnimationFile, parsedModel: Model): List<AnimationClip> {
        return file.animations.map { (name, anim) ->
            val nodeAnimations = mutableMapOf<Int, AnimationData>()

            anim.bones.forEach { (boneName, channels) ->

                val boneId = parsedModel.findNodeByName(boneName)?.index ?: run {
                    HollowEngine.LOGGER.warn(
                        "Animation '{}' targets bone '{}', which does not exist in this model - skipping",
                        name, boneName
                    )
                    return@forEach
                }

                val translation = channels.position?.let {
                    BedrockInterpolator(it.frames, BedrockInterpolator.PositionConverter)
                }
                val rotation = channels.rotation?.let {
                    BedrockInterpolator(it.frames, BedrockInterpolator.QuatConverter)
                }
                val scale = channels.scale?.let {
                    BedrockInterpolator(it.frames, BedrockInterpolator.Vec3Converter)
                }

                if (translation != null || rotation != null || scale != null) {
                    nodeAnimations[boneId] = AnimationData(translation, rotation, scale, null)
                }
            }

            AnimationClip(
                name,
                nodeAnimations,
                anim.animationLength ?: nodeAnimations.values.maxOfOrNull { it.duration } ?: 0f,
            )
        }
    }

    private fun ResourceLocation.open(side: ModelSide) =
        when (side) {
            ModelSide.CLIENT -> stream
            ModelSide.SERVER -> ModelResourceIO.open(this)
        }

    private fun ResourceLocation.exists(side: ModelSide): Boolean =
        when (side) {
            ModelSide.CLIENT -> exists()
            ModelSide.SERVER -> ModelResourceIO.exists(this)
        }
}
