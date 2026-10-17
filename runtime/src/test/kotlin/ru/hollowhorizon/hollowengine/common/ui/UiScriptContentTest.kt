package ru.hollowhorizon.hollowengine.common.ui

import ru.hollowhorizon.hollowengine.client.slots.PlayerInventory
import ru.hollowhorizon.hollowengine.client.slots.Slot
import ru.hollowhorizon.hollowengine.client.slots.SlotGrid
import ru.hollowhorizon.hollowengine.client.slots.Slots
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.common.data.dataKey
import ru.hollowhorizon.hollowengine.common.dialogue.DialogueChoiceView
import ru.hollowhorizon.hollowengine.common.dialogue.DialogueChoicesView
import ru.hollowhorizon.hollowengine.common.dialogue.DialogueUiKeys
import ru.hollowhorizon.hollowengine.common.scripting.ui.UiScript
import ru.hollowhorizon.hollowengine.common.ui.net.UiSurfaceKind
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Compiles the shape a real `.ui.kts` body has composables, data reads and `send` from an event
 * handler, so a change to the widget or scope API that would break every UI script fails here
 * rather than at script-compile time in game.
 */
class UiScriptContentTest {
    @AfterTest
    fun tearDown() = UiDefinitionRegistry.clear()

    private val Title = dataKey<String>("title") { "No quest" }
    private val Progress = dataKey<Int>("progress") { 0 }
    private val Entries = dataKey<List<String>>("entries") { emptyList() }

    @Test
    fun `a server-driven screen body compiles against the widget and scope API`() {
        object : UiScript() {
            init {
                screen("mypack:quest_log") {
                    pausesGame = false
                    content {
                        Column(id = "quest-root", modifier = Modifier.padding(12.px).gap(6.px)) {
                            Text(data[Title])
                            Text(data[Progress].toString())
                            data[Entries].forEach { entry -> Text(entry) }
                            Box(modifier = Modifier.onClick { send { putString("action", "accept") } }) {
                                Text("Accept")
                            }
                        }
                    }
                }
            }
        }

        assertNotNull(UiDefinitionRegistry.screen("mypack:quest_log".rl))
    }

    @Test
    fun `a client-only overlay body compiles without touching the server`() {
        object : UiScript() {
            init {
                overlay("mypack:block_hint") {
                    anchor = "crosshair"
                    placement = HudPlacement.AFTER
                    autoShow = true
                    content {
                        // A purely client-side overlay never calls send(); nothing forces a round trip.
                        if (data[Title].isNotEmpty()) {
                            Box(modifier = Modifier.padding(4.px)) { Text(data[Title]) }
                        }
                    }
                }
            }
        }

        assertNotNull(UiDefinitionRegistry.overlay("mypack:block_hint".rl))
    }

    @Test
    fun `a slot screen body compiles against the slot widget API`() {
        object : UiScript() {
            init {
                screen("mypack:npc_gear") {
                    content {
                        // The exact shape run/hollowengine/scripts/ui/npc_gear.ui.kts has. Those scripts are
                        // only compiled in game, so this is what catches a rename of the widget API.
                        Slots {
                            Column(modifier = Modifier.padding(12.px).gap(8.px)) {
                                Text(data[Title])
                                Row(modifier = Modifier.gap(6.px)) {
                                    SlotGrid("gear", columns = 1, range = 0..3)
                                    SlotGrid("gear", columns = 1, range = 4..5)
                                }
                                SlotGrid("npc", columns = 9)
                                Slot("npc", 0, modifier = Modifier.size(20.px, 20.px))
                                PlayerInventory()
                            }
                        }
                    }
                }
            }
        }

        assertNotNull(UiDefinitionRegistry.screen("mypack:npc_gear".rl))
    }

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
