package ru.hollowhorizon.hollowengine.addons.narrative.client

import ru.hollowhorizon.hollowengine.addons.narrative.ide.StoryScriptingAnalyzer
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeScriptTemplate
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeLanguageService
import ru.hollowhorizon.hollowengine.client.ui.ide.registerIdeLanguage
import ru.hollowhorizon.hollowengine.client.ui.ide.registerIdeScriptTemplate
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene.CameraPoseSources
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonExtensions
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.ui.UiDefinitionRegistry

/** What the addon adds to the client: the dialogue window and camera, and `.story` support in the IDE. */
@ClientOnly
internal object NarrativeClient {
    fun register(extensions: HollowAddonExtensions) {
        extensions.register(UiDefinitionRegistry.builtInScreens, "dialogue", DialogueScreenUi.definition)
        extensions.register(CameraPoseSources.point, "story", StoryCameraSystem)
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
