package ru.hollowhorizon.hollowengine.bootstrap

import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinProvider
import ru.hollowhorizon.hollowengine.common.scripting.mixins.MixinScriptStage
import ru.hollowhorizon.hollowengine.common.utils.RuntimeFlags

/** Created by the bootstrap by name, like [RuntimeBridgeEntrypoint]; see [ScriptMixinProvider] for the rules. */
class ScriptMixinProviderEntrypoint : ScriptMixinProvider {
    override fun collect(): Map<String, ByteArray> {
        RuntimeFlags.preparingMixins = true
        try {
            return MixinScriptStage.collect()
        } finally {
            RuntimeFlags.preparingMixins = false
        }
    }
}
