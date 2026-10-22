package ru.hollowhorizon.hollowengine.common.addons

import kotlinx.coroutines.CoroutineScope
import ru.hollowhorizon.hollowengine.common.events.Event
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import java.io.File

/**
 * The addon [id] has loaded: its [classes] are on [classLoader] and its listeners are subscribed. Lets other addons
 * find declarations of their own in it, the way `@SubscribeEvent` methods are found. [scope] ends when it unloads.
 *
 * @param packagePrefix the package an addon embedded in a mod keeps its classes in, or null for a whole jar.
 */
class HollowAddonLoadedEvent(
    val id: String,
    val classes: File,
    val classLoader: ClassLoader,
    val scope: CoroutineScope,
    val packagePrefix: String?,
) : Event {
    companion object : EventHandler<HollowAddonLoadedEvent>()
}
