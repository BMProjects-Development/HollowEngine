package ru.hollowhorizon.hollowengine.client.models.fbx

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.common.utils.Color
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import java.io.IOException
import java.util.Locale
import ru.hollowhorizon.hollowengine.client.models.internal.Material as InternalMaterial

internal fun Material.convert(model: ResourceLocation, side: ModelSide): InternalMaterial {
    var diffuse = InternalMaterial.MISSING_TEXTURE
    var normal = InternalMaterial.MISSING_NORMAL
    var specular = InternalMaterial.MISSING_SPECULAR
    var emissive = false
    layeredTextures.values.forEach { layer ->
        if (layer.textures.size > 1) domError("layered texture compositing is unsupported", layer.element)
    }
    val sources = layeredTextures.mapValues { it.value.textures.firstOrNull() }.filterValues { it != null }
        .mapValues { it.value!! } + textures
    for ((type, texture) in sources) {
        val media = texture.media
        val embedded = media?.imageContent
        val path = texture.relativeFileName.ifEmpty { media?.relativeFileName.orEmpty() }
            .ifEmpty { texture.fileName }.ifEmpty { media?.fileName.orEmpty() }
            .ifEmpty { texture.props("Path", "") }.ifEmpty { media?.props?.invoke("Path", "").orEmpty() }
        val resource = if (embedded != null) {
            model.withPath("${model.path}/embedded/${media.id}").also { target ->
                if (side == ModelSide.CLIENT) RenderSystem.recordRenderCall {
                    try {
                        Minecraft.getInstance().textureManager.register(target, DynamicTexture(NativeImage.read(embedded)))
                    } catch (ex: IOException) {
                        HollowEngine.LOGGER.error("Failed to decode embedded FBX texture $target", ex)
                    }
                }
            }
        } else resolveTexture(model, path) ?: continue
        when (type) {
            "DiffuseColor", "DiffuseFactor", "Maya|baseColor" -> diffuse = resource
            "NormalMap", "Bump", "Normal" -> normal = resource
            "SpecularColor", "SpecularFactor", "Reflection" -> specular = resource
            "EmissiveColor", "EmissiveFactor" -> emissive = true
        }
    }
    val color = props("DiffuseColor", Vec3f.ONES)
    val factor = props("DiffuseFactor", 1f)
    val opacity = (1f - props("TransparencyFactor", 0f)).coerceIn(0f, 1f)
    return InternalMaterial(
        name = name.substringAfter("::"), color = Color(color.x * factor, color.y * factor, color.z * factor, opacity),
        texture = diffuse, normalTexture = normal, specularTexture = specular,
        doubleSided = props("DoubleSided", false),
        blend = if (opacity < 1f) InternalMaterial.Blend.BLEND else InternalMaterial.Blend.OPAQUE,
        emissive = emissive,
    )
}

internal fun resolveTexture(model: ResourceLocation, fileName: String): ResourceLocation? {
    if (fileName.isBlank()) return null
    val path = fileName.replace('\\', '/').lowercase(Locale.ROOT)
    val relative = if (path.startsWith('/') || (path.length > 1 && path[1] == ':')) path.substringAfterLast('/') else path
    val segments = model.path.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
    for (segment in relative.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex) else return null
            else -> segments += segment
        }
    }
    return ResourceLocation.tryBuild(model.namespace, segments.joinToString("/"))
}
