package ru.hollowhorizon.hollowengine.addons.lighting

import kotlinx.coroutines.CoroutineScope
import ru.hollowhorizon.hollowengine.addons.lighting.client.LightingClient
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonContext
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonEntrypoint
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptor
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentSyncPolicy
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentTypes
import ru.hollowhorizon.hollowengine.common.utils.isPhysicalClient
import ru.hollowhorizon.hollowengine.common.utils.rl

class HollowLightingAddon : HollowAddonEntrypoint {
    private var rigRegistration: ExtensionHandle? = null

    override suspend fun load(context: HollowAddonContext, scope: CoroutineScope) {
        ComponentDescriptorRegistry.register(
            ComponentDescriptor(
                id = COMPONENT_ID.rl,
                value = LightComponent::class,
                serializer = LightComponent.serializer(),
                syncPolicy = ComponentSyncPolicy.SYNC,
                defaultFactory = ::LightComponent,
            )
        )
        try {
            rigRegistration = RigAttachmentTypes.register(LightAttachmentSpec.TYPE)
            if (isPhysicalClient) LightingClient.load(scope)
        } catch (failure: Throwable) {
            unregisterTypes()
            throw failure
        }
    }

    override suspend fun unload(context: HollowAddonContext) {
        try {
            if (isPhysicalClient) LightingClient.unload()
        } finally {
            unregisterTypes()
        }
    }

    private fun unregisterTypes() {
        rigRegistration?.close()
        rigRegistration = null
        ComponentDescriptorRegistry.unregisterDescriptor(COMPONENT_ID.rl)
    }

    private companion object {
        const val COMPONENT_ID = "hollowengine-lighting:light"
    }
}
