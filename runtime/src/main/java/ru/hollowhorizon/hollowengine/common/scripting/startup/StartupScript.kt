package ru.hollowhorizon.hollowengine.common.scripting.startup

import ru.hollowhorizon.hollowengine.common.registry.HollowRegistry

/**
 * Base of `*.startup.kts`. It runs once while the game starts, before registries freeze, on the client and
 * on a dedicated server alike: that is where items, blocks and other loads.
 */
abstract class StartupScript(val isClientSide: Boolean, namespace: String) : HollowRegistry(namespace)
