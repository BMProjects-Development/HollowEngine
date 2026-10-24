package ru.hollowhorizon.hollowengine.common.ui

import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything `.ui.kts` scripts have declared. Scripts are recompiled on reload, so the registry is
 * rebuilt from scratch each time rather than merged, a renamed or deleted screen must not linger.
 */
object UiDefinitionRegistry {
    /**
     * Screens addons ship. They outlive script reloads and are used only while no script declares a
     * screen with the same id, so a project can replace any of them.
     */
    val builtInScreens = ExtensionPoints.create("hollowengine:ui/screens".rl, UiScreenDefinition::class)

    private val screens = ConcurrentHashMap<ResourceLocation, UiScreenDefinition>()
    private val overlays = ConcurrentHashMap<ResourceLocation, UiOverlayDefinition>()
    private val surfaces = ConcurrentHashMap<ResourceLocation, UiSurfaceDefinition>()
    private val panels = ConcurrentHashMap<ResourceLocation, UiPanelDefinition>()

    /** Replaced screen class name -> the scripted screen that replaces it. */
    private val overrides = ConcurrentHashMap<String, OverrideEntry>()

    private class OverrideEntry(val definition: UiScreenDefinition, val includeSubclasses: Boolean)

    val allScreens: Collection<UiScreenDefinition> get() = screens.values
    val allOverlays: Collection<UiOverlayDefinition> get() = overlays.values

    val hasScreenOverrides: Boolean get() = overrides.isNotEmpty()

    fun register(definition: UiScreenDefinition) {
        screens[definition.id] = definition
        definition.overrides.forEach { override ->
            val previous = overrides.put(override.className, OverrideEntry(definition, override.includeSubclasses))
            if (previous != null && previous.definition.id != definition.id) {
                HollowEngine.LOGGER.warn(
                    "UI screen {} overrides {}, declared in {}",
                    definition.id, override.className, previous.definition.id,
                )
            }
        }
    }

    fun register(definition: UiOverlayDefinition) {
        overlays[definition.id] = definition
    }

    fun register(definition: UiSurfaceDefinition) {
        surfaces[definition.id] = definition
    }

    fun screen(id: ResourceLocation): UiScreenDefinition? =
        screens[id] ?: builtInScreens.extensions.firstOrNull { it.id == id }

    fun overlay(id: ResourceLocation): UiOverlayDefinition? = overlays[id]

    fun surface(id: ResourceLocation): UiSurfaceDefinition? = surfaces[id]

    fun register(definition: UiPanelDefinition) {
        panels[definition.id] = definition
    }

    fun panel(id: ResourceLocation): UiPanelDefinition? = panels[id]

    fun screenOverride(type: Class<*>): UiScreenDefinition? {
        if (overrides.isEmpty()) return null
        var current: Class<*>? = type
        var depth = 0
        while (current != null && current != Any::class.java) {
            val entry = overrides[current.name]
            if (entry != null && (depth == 0 || entry.includeSubclasses)) return entry.definition
            current = current.superclass
            depth++
        }
        return null
    }

    fun clear() {
        screens.clear()
        overlays.clear()
        surfaces.clear()
        panels.clear()
        overrides.clear()
    }
}
