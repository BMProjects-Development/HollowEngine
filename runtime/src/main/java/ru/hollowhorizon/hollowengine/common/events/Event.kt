package ru.hollowhorizon.hollowengine.common.events

interface Event

interface Cancellable {
    var isCanceled: Boolean
}

/** Posted only on the logical client. */
interface ClientEvent : Event

/**
 * Posted only on the logical server.
 */
interface ServerEvent : Event

/**
 * Fires once while the game starts, before any world is opened or resources are reloaded.
 */
interface StartupEvent : Event
