package ru.hollowhorizon.hollowengine.runtime.bootstrap

import ru.hollowhorizon.hollowengine.common.addons.HollowAddonManager
import ru.hollowhorizon.hollowengine.common.runtime.*

class HollowEngineRuntimeBootstrap : HollowRuntimeEntrypoint {
    private val runtimeIndex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ClassGraphRuntimeAnnotationIndex.create()
    }

    override val annotationIndex: RuntimeAnnotationIndex
        get() = runtimeIndex

    override fun initialize(context: HollowRuntimeBootstrapContext) {
        context.logger.info(
            "Preparing isolated HollowEngine runtime, cacheDir={}, production={}",
            context.cacheDirectory,
            context.isProduction
        )
        RuntimeAnnotationEnvironment.annotationIndex = runtimeIndex
    }

    override fun close() {
        HollowAddonManager.close()
        RuntimeAnnotationEnvironment.annotationIndex = EmptyRuntimeAnnotationIndex
        runtimeIndex.close()
    }
}
