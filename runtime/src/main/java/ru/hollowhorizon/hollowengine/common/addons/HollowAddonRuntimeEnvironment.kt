package ru.hollowhorizon.hollowengine.common.addons

import net.fabricmc.loader.api.FabricLoader
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform
import ru.hollowhorizon.hollowengine.common.utils.RuntimeFlags

internal object HollowAddonRuntimeEnvironment {
    lateinit var platform: RuntimePlatform

    val isClient: Boolean
        get() = RuntimeFlags.physicalClient

    fun mappingNamespace(): HollowAddonMappingNamespace = when (platform) {
        RuntimePlatform.FABRIC -> resolveMappingNamespace(
            FabricLoader.getInstance().mappingResolver.currentRuntimeNamespace,
        )

        RuntimePlatform.NEOFORGE -> HollowAddonMappingNamespace.OFFICIAL
    }

    internal fun resolveMappingNamespace(runtimeNamespace: String): HollowAddonMappingNamespace =
        HollowAddonMappingNamespace.entries.firstOrNull { namespace -> namespace.id == runtimeNamespace }
            ?: error("Unsupported runtime mapping namespace '$runtimeNamespace'")
}
