package ru.hollowhorizon.hollowengine.common.registry

import com.mojang.blaze3d.vertex.DefaultVertexFormat
import net.minecraft.client.renderer.ShaderInstance
import ru.hollowhorizon.hollowengine.HollowEngine.MODID
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.StaticGeometry
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxMeshRenderer
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxQuadRenderer
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.registry.RegisterShadersEvent
import ru.hollowhorizon.hollowengine.common.utils.rl

@ClientOnly
object ModShaders {
    lateinit var GLTF_ENTITY: ShaderInstance
    lateinit var GLTF_ENTITY_INSTANCED: ShaderInstance
    var VFX_PARTICLE: ShaderInstance? = null
    var VFX_MESH: ShaderInstance? = null
    var VFX_RIBBON: ShaderInstance? = null
    var UI_EFFECT: ShaderInstance? = null
    var UI_IMAGE_SHADOW: ShaderInstance? = null
    var MSDF_TEXT: ShaderInstance? = null

    @SubscribeEvent
    fun onShaderRegistry(event: RegisterShadersEvent) {
        event.register(
            "$MODID:gltf_entity-1.21.1".rl,
            DefaultVertexFormat.NEW_ENTITY
        ) {
            GLTF_ENTITY = it
        }
        event.register(
            "$MODID:gltf_entity_instanced-1.21.1".rl,
            DefaultVertexFormat.NEW_ENTITY
        ) {
            GLTF_ENTITY_INSTANCED = it
            StaticGeometry.invalidateBindings()
        }
        event.register(
            "$MODID:vfx_particle".rl,
            DefaultVertexFormat.POSITION_TEX_COLOR
        ) {
            VFX_PARTICLE = it
            VfxQuadRenderer.invalidate()
        }
        event.register(
            "$MODID:vfx_mesh".rl,
            DefaultVertexFormat.POSITION_TEX
        ) {
            VFX_MESH = it
            VfxMeshRenderer.invalidate()
        }
        event.register("$MODID:vfx_ribbon".rl, DefaultVertexFormat.PARTICLE) {
            VFX_RIBBON = it
        }
        event.register(
            "$MODID:ui_effect".rl,
            DefaultVertexFormat.POSITION_TEX_COLOR
        ) {
            UI_EFFECT = it
        }
        event.register(
            "$MODID:msdf_text".rl,
            DefaultVertexFormat.POSITION_TEX_COLOR
        ) {
            MSDF_TEXT = it
        }
        event.register("$MODID:ui_image_shadow".rl, DefaultVertexFormat.POSITION_TEX_COLOR) {
            UI_IMAGE_SHADOW = it
        }
    }
}
