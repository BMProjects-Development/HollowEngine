package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import ru.hollowhorizon.hollowengine.addons.narrative.HollowNarrativeAddon
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActions
import java.io.File

/**
 * The actions of the narrative addon in tests, which load no addons: found in its classes by the same scan that
 * registers them when the addon loads in the game.
 */
object NarrativeActions {
    const val NAMESPACE = "hollowengine-narrative"

    fun register() {
        val type = HollowNarrativeAddon::class.java
        val classes = File(type.protectionDomain.codeSource.location.toURI())
        StoryActions.registerFound(classes, type.classLoader, type.packageName, NAMESPACE)
    }
}
