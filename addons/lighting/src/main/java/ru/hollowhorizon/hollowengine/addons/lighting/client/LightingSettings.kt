package ru.hollowhorizon.hollowengine.addons.lighting.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.client.Minecraft
import org.qualet.irl.light.LightBuffer
import org.qualet.irl.light.LightRegistry
import org.qualet.irl.light.VlGlobalsBuffer
import org.qualet.irl.light.shadow.ShadowConfig
import java.nio.file.Files

@Serializable
data class LightingOptions(
    val maxShaderLights: Int = 256,
    val shadowQuality: Int = 1,
    val shadowCache: Boolean = true,
    val shadowBakeBudget: Int = 4,
    val shadowBlocks: Boolean = true,
    val shadowBlockRadius: Int = 24,
    val shadows: Boolean = true,
    val shadowSoftness: Float = 0.1f,
    val intensity: Float = 1f,
    val specular: Boolean = true,
    val specularIntensity: Float = 1f,
    val volumetricIntensity: Float = 1f,
    val volumetricDistance: Float = 96f,
    val volumetricSteps: Int = 48,
    val volumetricNoise: Boolean = true,
) {
    init {
        require(maxShaderLights in 1..LightBuffer.MAX_LIGHTS)
        require(shadowQuality in 0..3 && shadowBakeBudget >= 0 && shadowBlockRadius in 1..128)
        require(shadowSoftness.isFinite() && shadowSoftness >= 0f)
        require(intensity.isFinite() && intensity >= 0f && specularIntensity.isFinite() && specularIntensity >= 0f)
        require(volumetricIntensity.isFinite() && volumetricIntensity >= 0f)
        require(volumetricDistance.isFinite() && volumetricDistance > 0f && volumetricSteps in 1..96)
    }
}

object LightingSettings {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    @Volatile
    var options = LightingOptions()
        private set

    val shadows: ShadowConfig = object : ShadowConfig {
        override fun shadowQuality() = options.shadowQuality
        override fun shadowCache() = options.shadowCache
        override fun shadowBakeBudget() = options.shadowBakeBudget
        override fun shadowBlocks() = options.shadowBlocks
        override fun shadowBlockRadius() = options.shadowBlockRadius
        override fun shadowsEnabled() = options.shadows
    }

    fun initialize() {
        val file = Minecraft.getInstance().gameDirectory.toPath().resolve("config/hollowengine-lighting.json")
        if (Files.exists(file)) options = json.decodeFromString(LightingOptions.serializer(), Files.readString(file))
        else {
            Files.createDirectories(file.parent)
            Files.writeString(file, json.encodeToString(LightingOptions.serializer(), options))
        }
    }

    fun applyGlobals() {
        val config = options
        LightRegistry.setUploadCap(config.maxShaderLights)
        LightBuffer.setVlGlobalIntensity(config.volumetricIntensity)
        LightBuffer.setVlFlags((if (config.shadows) 1 else 0) or (if (config.volumetricNoise) 2 else 0))
        VlGlobalsBuffer.set(
            config.volumetricIntensity,
            config.volumetricDistance,
            1.5f,
            1.5f,
            0.6f,
            2f,
            0.25f,
            0f,
            config.volumetricSteps,
            2,
            2,
            (if (config.shadows) 1 else 0) or (if (config.volumetricNoise) 2 else 0) or 0x7C
        )
        VlGlobalsBuffer.setShadow(config.shadows, config.shadowSoftness)
        VlGlobalsBuffer.setSurface(true, config.intensity, config.specular, config.specularIntensity, false, 3, 0.1f)
    }
}
