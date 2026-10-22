package ru.hollowhorizon.hollowengine.common.scripting

import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptId

/**
 * A script instance of [id] could not start on the server: the script is gone, does not compile, or threw while
 * starting. Addons drop what waited for such an instance instead of waiting forever.
 */
class ScriptFailedEvent(val id: ScriptId) : Event {
    companion object : EventHandler<ScriptFailedEvent>()
}
