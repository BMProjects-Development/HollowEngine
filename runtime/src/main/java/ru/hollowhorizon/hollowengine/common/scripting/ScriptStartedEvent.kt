package ru.hollowhorizon.hollowengine.common.scripting

import kotlinx.coroutines.CoroutineScope
import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId

/**
 * A script instance has run its body and is about to start: a node, a reload script or a startup script.
 * Addons pick up the instances they work with here.
 *
 * @param scope lives exactly as long as the instance does.
 * @param entry what the instance was asked to start with, such as the stage named by `/hollowengine scripting run`.
 */
class ScriptStartedEvent(
    val id: ScriptId,
    val script: Any,
    val scope: CoroutineScope,
    val entry: String? = null,
) : Event {
    companion object : EventHandler<ScriptStartedEvent>()
}
