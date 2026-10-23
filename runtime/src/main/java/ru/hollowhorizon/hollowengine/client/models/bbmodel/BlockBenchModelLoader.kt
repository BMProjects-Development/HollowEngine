package ru.hollowhorizon.hollowengine.client.models.bbmodel

import kotlinx.serialization.json.*
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.models.bedrock.BedrockExpressions
import ru.hollowhorizon.hollowengine.client.models.internal.*
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelLoader
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.client.utils.stream
import ru.hollowhorizon.hollowengine.common.models.ModelResourceIO
import ru.hollowhorizon.hollowengine.common.utils.math.*

object BlockBenchModelLoader : ModelLoader {
    override val supportedFormats = setOf("bbmodel")

    override suspend fun load(location: ResourceLocation, side: ModelSide): Model {
        val source = when (side) {
            ModelSide.CLIENT -> location.stream
            ModelSide.SERVER -> ModelResourceIO.open(location)
        }.bufferedReader().use { it.readText() }
        return convert(BlockBenchModel.parse(source), location, side)
    }

    /** No render state is touched during conversion; embedded textures upload on their first bind. */
    fun convert(file: BlockBenchModel, location: ResourceLocation, side: ModelSide): Model =
        BlockBenchConversion(file, location, side).convert()
}

internal fun List<Float>.bbVector(): Vec3f {
    require(size == 3 && all(Float::isFinite)) { "Expected a finite three component Blockbench vector: $this" }
    return Vec3f(this[0], this[1], this[2])
}

/** Blockbench's generic and Bedrock projects use THREE.Euler's ZYX order. */
internal fun bbRotation(degrees: Vec3f): QuatF =
    MutableQuatF().rotate(degrees.z.deg, Vec3f.Z_AXIS).rotate(degrees.y.deg, Vec3f.Y_AXIS)
        .rotate(degrees.x.deg, Vec3f.X_AXIS)

internal class BBNode(val element: BBElement, val index: Int, val parent: BBNode?) {
    val children = mutableListOf<BBNode>()
    val pivot = (element.position ?: element.origin).bbVector()
    val transform = TrsTransformF().apply {
        val absoluteParent = parent?.takeIf { it.element.type == "group" && element.type != "armature" }
        translate(if (absoluteParent == null || element.type == "armature_bone") pivot else pivot - absoluteParent.pivot)
        rotate(bbRotation(element.rotation.bbVector()))
        scale(element.scale.bbVector())
    }
    val global: Mat4f = parent?.global?.mul(transform.matrixF, MutableMat4f()) ?: transform.matrixF
    val exportsGeometry: Boolean = element.export && (parent?.exportsGeometry ?: true)
}

private class BlockBenchConversion(
    private val file: BlockBenchModel,
    private val location: ResourceLocation,
    private val side: ModelSide,
) {
    private val definitions = (file.groups + file.elements).associateBy { it.uuid }
    private val claimed = HashSet<String>()
    private val nodes = linkedMapOf<String, BBNode>()
    private val textureMaterials = file.textures.mapIndexed { index, texture ->
        val content = if (side == ModelSide.CLIENT) BBTextureContent(texture, file, location) else null
        Material(
            name = texture.name.ifBlank { "texture_$index" },
            doubleSided = texture.renderSides != "front",
            blend = when {
                texture.renderMode == "additive" -> Material.Blend.ADDITIVE
                texture.forceTranslucent || content?.hasTranslucency == true -> Material.Blend.BLEND
                else -> Material.Blend.OPAQUE
            },
            emissive = texture.renderMode == "emissive",
            textureContent = content,
        )
    }
    private val untextured = Material(name = "untextured", doubleSided = true)

    fun convert(): Model {
        require(file.meta.version.substringBefore('.').toInt() in 4..5) {
            "Unsupported Blockbench project version ${file.meta.version} in $location"
        }
        require(definitions.size == file.groups.size + file.elements.size) { "Duplicate outliner UUID in $location" }
        val roots = file.nodes.map { readNode(it, null) }.toMutableList()

        file.elements.filter { it.uuid !in claimed }.forEach { element ->
            roots += readNode(JsonPrimitive(element.uuid), null)
        }
        val geometry = BBGeometry(file, textureMaterials, untextured)
        fun build(node: BBNode): NodeDefinition {
            val parts = if (node.exportsGeometry) geometry.convert(node.element) else emptyList()
            val skin = skin(node)
            val primitives = if (skin != null) BBArmature.applyWeights(node, skin, nodes.values, parts)
            else parts.map { it.primitive }
            val children = node.children.map(::build).toMutableList()
            return NodeDefinition(
                index = node.index,
                name = node.element.name.ifBlank { node.element.uuid },
                children = children,
                transform = node.transform,
                mesh = primitives.takeIf { it.isNotEmpty() }?.let { Mesh(it, floatArrayOf()) },
                skin = skin,
                visible = node.element.visibility,
            ).also { definition -> children.forEach { it.parent = definition } }
        }

        val scenes = listOf(Scene(ModelSpace.place(roots.map(::build), facesPositiveZ = false, unitsPerBlock = 16f)))
        return Model(
            scene = 0,
            scenes = scenes,
            materials = (textureMaterials + untextured).toSet(),
            animations = BedrockExpressions.batch { BBAnimations.convert(file, nodes) },
            constraints = BBConstraints.convert(nodes),
            textureContents = textureMaterials.mapNotNull { it.textureContent },
        )
    }

    private fun readNode(raw: JsonElement, parent: BBNode?): BBNode {
        val id = when (raw) {
            is JsonPrimitive -> raw.content
            is JsonObject -> raw.getValue("uuid").jsonPrimitive.content
            else -> error("Invalid Blockbench outliner entry in $location: $raw")
        }
        require(claimed.add(id)) { "Duplicate or cyclic Blockbench outliner entry $id in $location" }
        val element = definitions[id] ?: (raw as? JsonObject)?.let {
            BlockBenchModel.JSON.decodeFromJsonElement<BBElement>(it)
        } ?: error("Missing Blockbench outliner element $id in $location")
        val node = BBNode(element, nodes.size, parent)
        nodes[id] = node
        val children = (raw as? JsonObject)?.get("children") as? JsonArray
        children?.forEach { child -> node.children += readNode(child, node) }
        return node
    }

    private fun skin(node: BBNode): Skin? {
        if (node.element.type != "mesh" || node.parent?.element?.type != "armature") return null
        val bones = BBArmature.bones(node.parent, nodes.values)
        if (bones.isEmpty()) return null

        val joints = bones + node
        return Skin(joints.map { it.index }, joints.map { joint ->
            MutableMat4f(joint.global).apply { invert() }.mul(node.global)
        }.toTypedArray())
    }
}
