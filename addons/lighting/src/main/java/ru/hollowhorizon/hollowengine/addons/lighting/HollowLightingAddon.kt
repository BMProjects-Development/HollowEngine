package ru.hollowhorizon.hollowengine.addons.lighting

import kotlinx.coroutines.CoroutineScope
import ru.hollowhorizon.hollowengine.addons.lighting.client.LightingClient
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonContext
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEntrypoint
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient

class HollowLightingAddon : HollowAddonEntrypoint {
    override suspend fun load(context: HollowAddonContext, scope: CoroutineScope) {
        if (isPhysicalClient) LightingClient.load(scope)
    }

    override suspend fun unload(context: HollowAddonContext) {
        if (isPhysicalClient) LightingClient.unload()
    }
}
