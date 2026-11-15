package ru.hollowhorizon.hollowengine.bootstrap.impl;

import net.neoforged.fml.loading.FMLEnvironment;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform;

/** The loader and physical side of the game, which the bootstrap needs before the runtime is told them. */
final class BootstrapEnvironment {
    private BootstrapEnvironment() {
    }

    static RuntimePlatform platform() {
        return RuntimePlatform.NEOFORGE;
    }

    static boolean isPhysicalClient() {
        return FMLEnvironment.dist.isClient();
    }
}
