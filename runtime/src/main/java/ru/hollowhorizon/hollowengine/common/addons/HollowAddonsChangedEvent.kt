package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler

/**
 * The set of loaded addons has changed. Fires once after a batch of loads and unloads, such as the
 * startup or an addon being enabled, so whatever was built from contributions of several addons can be
 * rebuilt once they are all in place.
 */
class HollowAddonsChangedEvent : Event {
    companion object : EventHandler<HollowAddonsChangedEvent>()
}
