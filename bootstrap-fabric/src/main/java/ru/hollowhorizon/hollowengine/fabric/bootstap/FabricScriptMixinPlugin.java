package ru.hollowhorizon.hollowengine.fabric.bootstap;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import ru.hollowhorizon.hollowengine.bootstrap.impl.mixins.ScriptMixinConfigPlugin;
import ru.hollowhorizon.hollowengine.bootstrap.impl.mixins.ScriptMixinJars;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimeBridge;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform;
import ru.hollowhorizon.hollowengine.fabric.internal.FabricModList;

import java.nio.file.Path;
import java.util.Map;

/** Knot reads mixin classes from its classpath, so the generated classes are written to a jar added to it. */
public final class FabricScriptMixinPlugin extends ScriptMixinConfigPlugin {
    @Override
    protected void configureRuntime(RuntimeBridge bridge) {
        bridge.setPlatform(RuntimePlatform.FABRIC);
        bridge.setProduction(!FabricLoader.getInstance().isDevelopmentEnvironment());
        bridge.setClient(isPhysicalClient());
        // A mixin script compiled now needs the mod files on its classpath.
        bridge.initModList(new FabricModList());
    }

    @Override
    protected boolean isPhysicalClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    @Override
    protected void provide(Map<String, byte[]> classes) throws Exception {
        Path jar = ScriptMixinJars.write(classes);
        FabricLauncherBase.getLauncher().addToClassPath(jar);
    }
}
