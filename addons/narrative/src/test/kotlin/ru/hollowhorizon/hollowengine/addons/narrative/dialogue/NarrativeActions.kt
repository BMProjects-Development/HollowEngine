package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryActions

/** The actions the narrative addon ships, registered for tests as the game registers them when the addon loads. */
object NarrativeActions {
    const val NAMESPACE = "hollowengine-narrative"

    private val CLASSES = listOf(
        "ru.hollowhorizon.hollowengine.addons.narrative.scene.SceneActionsKt",
        "ru.hollowhorizon.hollowengine.addons.narrative.scene.ScreenEffectsKt",
        "ru.hollowhorizon.hollowengine.addons.narrative.npcs.NpcActionsKt",
        "ru.hollowhorizon.hollowengine.addons.narrative.npcs.NpcHandsKt",
    )

    fun register() = CLASSES.forEach { StoryActions.register(Class.forName(it), NAMESPACE) }
}
