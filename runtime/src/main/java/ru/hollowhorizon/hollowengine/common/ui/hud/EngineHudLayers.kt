package ru.hollowhorizon.hollowengine.common.ui.hud

import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderArmEvent
import ru.hollowhorizon.hollowengine.common.ui.hud.EngineHudLayers.HAND
import ru.hollowhorizon.hollowengine.common.utils.rl

/**
 * Things the engine can hide that vanilla does not call HUD layers, addressed the same way so that
 * one hide list covers them. A caller says `hideHudLayers(EngineHudLayers.HAND)` and never has to
 * know that the hand is drawn by the world renderer rather than by the HUD.
 */
object EngineHudLayers {
    /** The first-person arm and whatever it is holding. */
    val HAND: ResourceLocation = "hollowengine:hand".rl

    val all: Set<ResourceLocation> = setOf(HAND)

    /**
     * Parses a layer name from a script or a story. A bare name is looked up among the engine's own
     * layers first, then treated as vanilla, so `hand` reaches [HAND] rather than becoming
     * `minecraft:hand`, which is nothing.
     */
    fun parse(name: String): ResourceLocation {
        if (':' in name) return ResourceLocation.parse(name)
        return all.firstOrNull { it.path == name } ?: VanillaHudLayers.parse(name)
    }
}

/** Applies [EngineHudLayers.HAND] where the arm is actually drawn. */
@ClientOnly
object EngineHudLayerRendering {
    @SubscribeEvent
    fun onRenderArm(event: RenderArmEvent) {
        if (HudLayerRegistry.isHidden(HAND)) event.isCanceled = true
    }
}
