package ru.hollowhorizon.hollowengine.common.scripting.reload

import ru.hollowhorizon.hollowengine.common.events.ServerEvent
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

/**
 * Brackets the server reload scripts of a datapack load, so a registry the scripts fill can be
 * reset before they run and published after.
 */
sealed class ServerReloadScriptsEvent : ServerEvent {
    /** The scripts are about to run. */
    class Pre : ServerReloadScriptsEvent() {
        companion object : EventHandler<Pre>()
    }

    /** The scripts have run, even if some of them failed or were skipped. */
    class Post : ServerReloadScriptsEvent() {
        companion object : EventHandler<Post>()
    }
}
