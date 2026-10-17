package ru.hollowhorizon.hollowengine.client.models.obj

import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.ModelSpace
import ru.hollowhorizon.hollowengine.client.models.internal.Scene
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.client.utils.stream
import ru.hollowhorizon.hollowengine.common.models.ModelResourceIO
import ru.hollowhorizon.hollowengine.common.utils.Color
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.rl

class OBJModel(
    private var location: ResourceLocation,
    private var mtlLocation: ResourceLocation? = null,
    private val side: ModelSide = ModelSide.CLIENT,
) {
    private val vertices = mutableListOf<Vec3f>()
    private val textures = mutableListOf<Vec2f>()
    private val normals = mutableListOf<Vec3f>()
    private val objects = mutableListOf<OBJDataMesh>()
    private val materials = mutableMapOf<String, OBJMaterial>()
    private var isBlockBench = false

    init {
        readOBJ()
        readMTL()
    }

    fun readMTL() {
        var currentMaterial: OBJMaterial? = null

        val materialLocation = mtlLocation?.takeIf { it.exists() } ?: return
        materialLocation.open().bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                val tokens = line.trim().split("\\s+".toRegex()).toTypedArray()
                when (tokens.firstOrNull()) {
                    "newmtl" -> {
                        val name = processMaterialName(tokens[1])
                        currentMaterial = OBJMaterial(name).also {
                            materials[name] = it
                        }
                    }

                    "Kd" -> if (tokens.size >= 4) {
                        currentMaterial?.apply {
                            r = tokens[1].toFloat()
                            g = tokens[2].toFloat()
                            b = tokens[3].toFloat()
                            a = tokens.getOrNull(4)?.toFloat() ?: 1f
                        }
                    }

                    "map_Kd" -> {
                        currentMaterial?.hasTexture = true
                        val path = tokens[1]
                        currentMaterial?.texture = if (path.contains(":")) path.rl
                        else mtlLocation?.withPath(mtlLocation!!.path.substringBeforeLast("/") + "/" + path)
                            ?: error("MTL location is not set")
                    }

                    "map_Bump", "map_bump", "bump" -> {
                        val path = tokens[1]
                        currentMaterial?.normalTexture = if (path.contains(":")) path.rl
                        else mtlLocation?.withPath(mtlLocation!!.path.substringBeforeLast("/") + "/" + path)
                            ?: error("MTL location is not set")
                    }

                    "map_Ks", "map_specular", "refl" -> {
                        val path = tokens[1]
                        currentMaterial?.specularTexture = if (path.contains(":")) path.rl
                        else mtlLocation?.withPath(mtlLocation!!.path.substringBeforeLast("/") + "/" + path)
                            ?: error("MTL location is not set")
                    }

                    "map_Kd_linear" -> currentMaterial?.linear = true
                    "map_Kd_path" -> currentMaterial?.texture = tokens.dropFirst().joinToString("_").rl
                }
            }
        }
    }

    fun readOBJ() {
        var currentMesh: OBJDataMesh? = null
        var currentMaterial: OBJMaterial? = null

        location.open().bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                isBlockBench = isBlockBench || line.contains("blockbench", ignoreCase = true)
                val tokens = line.trim().split("\\s+".toRegex()).toTypedArray()
                val keyword = tokens[0]

                when (keyword) {
                    "o", "g" -> if (tokens.size >= 2) {
                        val name = tokens[1]
                        currentMesh = objects.find { it.name == name } ?: OBJDataMesh().apply {
                            this.name = name
                            objects.add(this)
                        }
                    }

                    "mtllib" -> {
                        val path = tokens[1]
                        mtlLocation = if (path.contains(":")) path.rl
                        else location.withPath(location.path.substringBeforeLast("/") + "/" + path)
                    }

                    "v" -> vertices += Vec3f(tokens[1].toFloat(), tokens[2].toFloat(), tokens[3].toFloat())
                    "vt" -> textures += Vec2f(tokens[1].toFloat(), tokens[2].toFloat())
                    "vn" -> normals += Vec3f(tokens[1].toFloat(), tokens[2].toFloat(), tokens[3].toFloat())
                    "usemtl" -> {
                        if (materials.isEmpty()) readMTL()
                        currentMaterial = materials[processMaterialName(tokens[1])]
                    }

                    "f" -> {
                        val faces = tokens.dropFirst()
                        val mesh = currentMesh ?: return@forEach

                        val faceList =
                            mesh.groups.getOrPut(currentMaterial ?: error("Material not found")) { mutableListOf() }

                        when (faces.size) {
                            3 -> faceList += OBJFace(faces)
                            4 -> {
                                faceList += OBJFace(arrayOf(faces[0], faces[1], faces[2]))
                                faceList += OBJFace(arrayOf(faces[0], faces[2], faces[3]))
                            }

                            else -> {
                                for (i in 1 until faces.size - 1) {
                                    faceList += OBJFace(arrayOf(faces[0], faces[i], faces[i + 1]))
                                }
                            }
                        }
                    }
                }
            }
        }

        if(normals.isEmpty()) generateNormals()
    }

    fun generateNormals() {
        val normalAccumulator = Array(vertices.size) { Vec3f(0f, 0f, 0f) }
        val normalCounts = IntArray(vertices.size)

        for (mesh in objects) {
            for ((_, faces) in mesh.groups) {
                for (face in faces) {
                    val g0 = face.idxGroups[0]
                    val g1 = face.idxGroups[1]
                    val g2 = face.idxGroups[2]

                    val v0 = vertices[g0.idxPos]
                    val v1 = vertices[g1.idxPos]
                    val v2 = vertices[g2.idxPos]

                    val edge1 = v1 - v0
                    val edge2 = v2 - v0

                    val faceNormal = edge1.cross(edge2, MutableVec3f()).norm()

                    for (g in face.idxGroups) {
                        normalAccumulator[g.idxPos] += faceNormal
                        normalCounts[g.idxPos] += 1
                    }
                }
            }
        }

        normals.clear()
        for (i in vertices.indices) {
            val summed = normalAccumulator[i]
            normals += if (normalCounts[i] > 0) summed.normed() else Vec3f(0f, 1f, 0f)
        }

        for (mesh in objects) {
            for ((_, faces) in mesh.groups) {
                for (face in faces) {
                    for (g in face.idxGroups) {
                        g.idxVecNormal = g.idxPos
                    }
                }
            }
        }
    }

    fun toInternalModel(): Model {
        val materials = materials.values.associate {
            it to
                    Material(
                        color = Color(it.r, it.g, it.b, it.a),
                        texture = it.texture ?: Material.MISSING_TEXTURE,
                        normalTexture = it.normalTexture ?: Material.MISSING_NORMAL,
                        specularTexture = it.specularTexture ?: Material.MISSING_SPECULAR,
                    )
        }
        val nodes = ModelSpace.place(
            objects.mapIndexed { i, it -> it.toNode(materials, vertices, normals, textures, i) },
            facesPositiveZ = !isBlockBench,
        )
        val model = Model(
            scene = 0,
            scenes = listOf(Scene(nodes)),
            materials = materials.values.toSet()
        )


        return model
    }


    private fun Array<String>.dropFirst(amount: Int = 1) = drop(amount).toTypedArray()
    private fun processMaterialName(name: String): String = name.replace("[/|\\\\]+".toRegex(), "-")

    private fun ResourceLocation.open() =
        when (side) {
            ModelSide.CLIENT -> stream
            ModelSide.SERVER -> ModelResourceIO.open(this)
        }

    private fun ResourceLocation.exists(): Boolean =
        when (side) {
            ModelSide.CLIENT -> true
            ModelSide.SERVER -> ModelResourceIO.exists(this)
        }

}
