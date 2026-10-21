package ru.hollowhorizon.hollowengine.addons.narrative.client

import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.CutsceneCameraSystem
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.editor.CutsceneTimeline
import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryScriptingAnalyzer
import ru.hollowhorizon.hollowengine.client.camera.CameraDirector
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeScriptTemplate
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeLanguageService
import ru.hollowhorizon.hollowengine.client.ui.ide.registerIdeLanguage
import ru.hollowhorizon.hollowengine.client.ui.ide.registerIdeScriptTemplate
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.IdeTimelines
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonExtensions
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.ui.UiDefinitionRegistry

/**
 * What the addon adds to the client: the dialogue window, the cutscene and dialogue cameras, the cutscene
 * timeline and `.story` support in the IDE.
 */
@ClientOnly
internal object NarrativeClient {
    /** A cutscene is the more specific instruction, so it holds the camera over a dialogue's own. */
    private const val CUTSCENE_CAMERA_PRIORITY = 10

    fun register(extensions: HollowAddonExtensions) {
        extensions.register(UiDefinitionRegistry.builtInScreens, "dialogue", DialogueScreenUi.definition)
        extensions.register(CameraDirector.sources, "cutscene", CutsceneCameraSystem, CUTSCENE_CAMERA_PRIORITY)
        extensions.register(CameraDirector.sources, "story", StoryCameraSystem)
        extensions.register(IdeTimelines.defaults, "cutscene", CutsceneTimeline)
        extensions.registerIdeLanguage(
            HollowIdeLanguageService.extensions("story", listOf("story")) { StoryScriptingAnalyzer },
        )
        extensions.registerIdeScriptTemplate(
            HollowIdeScriptTemplate.fromResource(
                id = "dialogue",
                label = "Dialogue",
                extension = ".story",
                defaultName = "dialogue",
                resource = "dialogue.story",
                owner = NarrativeClient::class.java,
            ),
        )
    }
}
