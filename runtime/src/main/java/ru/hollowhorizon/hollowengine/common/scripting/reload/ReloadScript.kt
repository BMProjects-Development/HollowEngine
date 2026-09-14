package ru.hollowhorizon.hollowengine.common.scripting.reload

import kotlinx.coroutines.CoroutineScope
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.world.item.crafting.RecipeManager

/**
 * Base of `*.reload.kts`. A script is run again on every reload of its side and is the scope of everything
 * it starts: its event handlers and coroutines are cancelled before the next run. Coroutines run on the
 * thread of the script's side.
 */
abstract class ReloadScript(scope: CoroutineScope, val resources: ResourceManager) : CoroutineScope by scope

/** Implicit receiver of server reload scripts, which run on every datapack load. */
class ServerReloadContext(val recipeManager: RecipeManager)
