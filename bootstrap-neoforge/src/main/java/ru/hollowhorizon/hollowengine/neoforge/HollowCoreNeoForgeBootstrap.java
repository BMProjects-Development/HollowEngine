package ru.hollowhorizon.hollowengine.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform;
import ru.hollowhorizon.hollowengine.neoforge.internal.NeoForgeFakePlayerFactory;
import ru.hollowhorizon.hollowengine.neoforge.internal.NeoForgeModList;
import ru.hollowhorizon.hollowengine.neoforge.internal.NeoForgeNetworkManager;
import ru.hollowhorizon.hollowengine.neoforge.internal.NeoForgeRegistryHolder;

@Mod("hollowengine")
public final class HollowCoreNeoForgeBootstrap {
    public HollowCoreNeoForgeBootstrap(IEventBus modBus) {
        // Normally undone by the first script mixin applied; a launch that applied none still gets it back.
        NeoForgeScriptMixinPlugin.restore();
        BootstrapRuntimeManager.bridge().setPlatform(RuntimePlatform.NEOFORGE);
        BootstrapRuntimeManager.bridge().setProduction(FMLEnvironment.production);
        BootstrapRuntimeManager.bridge().setClient(FMLEnvironment.dist.isClient());

        BootstrapRuntimeManager.bridge().initFakePlayers(new NeoForgeFakePlayerFactory());
        BootstrapRuntimeManager.bridge().initStackHelper(item -> item.getCraftingRemainingItem());
        BootstrapRuntimeManager.bridge().initNetwork(new NeoForgeNetworkManager());
        BootstrapRuntimeManager.bridge().initModList(new NeoForgeModList());
        BootstrapRuntimeManager.bridge().initRegistryProvider((location, registry, model, generator, type) ->
                new NeoForgeRegistryHolder<>(modBus, location, registry, model, generator, type));

        BootstrapRuntimeManager.bridge().onCommonInitialize();
        modBus.addListener(NeoForgeNetworkManager::onRegisterPackets);
        NeoForgeEvents.init(modBus);
        if (FMLEnvironment.dist.isClient()) {
            modBus.addListener(HollowCoreNeoForgeBootstrap::onClientInitialize);
            NeoForgeClientEvents.init(modBus);
        }
    }

    @OnlyIn(Dist.CLIENT)
    public static void onClientInitialize(FMLClientSetupEvent event) {
        BootstrapRuntimeManager.bridge().onClientInitialize();
    }
}
