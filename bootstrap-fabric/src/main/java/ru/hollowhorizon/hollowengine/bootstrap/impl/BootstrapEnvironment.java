package ru.hollowhorizon.hollowengine.bootstrap.impl;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform;

/** The loader and physical side of the game, which the bootstrap needs before the runtime is told them. */
final class BootstrapEnvironment {
    private BootstrapEnvironment() {
    }

    static RuntimePlatform platform() {
        return RuntimePlatform.FABRIC;
    }

    static boolean isPhysicalClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }
}
