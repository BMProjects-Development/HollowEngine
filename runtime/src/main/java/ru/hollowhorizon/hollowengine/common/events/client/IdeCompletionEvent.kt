package ru.hollowhorizon.hollowengine.common.events.client

import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextCompletion
import ru.hollowhorizon.hollowengine.common.events.ClientEvent
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

/**
 * Posted on the client thread for an IDE completion request. Handlers can contribute declarations
 * or snippets with imports, insertion text and a caret position, using the current world safely.
 * [items] starts with the language service's results and the engine's context snippets.
 */
class IdeCompletionEvent(
    val path: String,
    val text: String,
    val caret: Int,
    val items: MutableList<UiTextCompletion>,
) : ClientEvent {
    companion object : EventHandler<IdeCompletionEvent>()
}
