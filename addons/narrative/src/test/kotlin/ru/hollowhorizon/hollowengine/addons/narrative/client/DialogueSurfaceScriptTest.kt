package ru.hollowhorizon.hollowengine.addons.narrative.client

import ru.hollowhorizon.hollowengine.addons.narrative.scene.DialogueChoiceView
import ru.hollowhorizon.hollowengine.addons.narrative.scene.DialogueChoicesView
import ru.hollowhorizon.hollowengine.addons.narrative.scene.DialogueUiKeys
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.common.scripting.ui.UiScript
import ru.hollowhorizon.hollowengine.common.ui.UiData
import ru.hollowhorizon.hollowengine.common.ui.UiDefinitionRegistry
import ru.hollowhorizon.hollowengine.common.ui.UiGuiScale
import ru.hollowhorizon.hollowengine.common.ui.send
import ru.hollowhorizon.hollowengine.common.ui.net.UiSurfaceKind
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Compiles a `.ui.kts` dialogue surface against the dialogue UI contract, so a change to the keys the
 * presenter writes fails here rather than in a project's own dialogue window.
 */
class DialogueSurfaceScriptTest {
    @AfterTest
    fun tearDown() = UiDefinitionRegistry.clear()

    @Test
    fun `a surface switches between overlay and screen from its own document`() {
        object : UiScript() {
            init {
                surface("mypack:dialogue") {
                    anchor = "hotbar"
                    interactive()
                    closeOnEscape = false
                    guiScale = UiGuiScale.Auto
                    mode { document ->
                        if (document[DialogueUiKeys.Choices].options.isEmpty()) UiSurfaceKind.OVERLAY
                        else UiSurfaceKind.SCREEN
                    }
                    content {
                        val line = data[DialogueUiKeys.Line]
                        val choices = data[DialogueUiKeys.Choices]
                        Box(mode = UiBoxMode.FREE, modifier = Modifier.size(UiLength.Fill, UiLength.Fill)) {
                            var catcher: Modifier = Modifier
                            if (line.awaiting) {
                                catcher = catcher.input(clickable = true).onClick { event ->
                                    if (event.isRightClick()) {
                                        send { putString(DialogueUiKeys.Action, DialogueUiKeys.AdvanceAction) }
                                    }
                                }
                            }
                            Box(modifier = catcher)
                            Box(mode = UiBoxMode.FREE) {
                                Row {
                                    if (line.speakerEntity >= 0) Entity(line.speakerEntity)
                                    Text(line.text)
                                }
                                Box(modifier = if (line.awaiting) Modifier.state(UiState.of("ready")) else null) {
                                    Image("hollowengine:textures/gui/icons/mouse_right.png")
                                }
                            }
                            choices.options.forEach { option -> Text(option.text) }
                        }
                    }
                }
            }
        }

        val surface = assertNotNull(UiDefinitionRegistry.surface("mypack:dialogue".rl))
        assertEquals(UiSurfaceKind.OVERLAY, surface.mode(UiData()), "a line reads over the world")
        val menu = UiData().apply {
            this[DialogueUiKeys.Choices] = DialogueChoicesView(listOf(DialogueChoiceView(0, "Yes")))
        }
        assertEquals(UiSurfaceKind.SCREEN, surface.mode(menu), "a menu needs the cursor")
    }
}
