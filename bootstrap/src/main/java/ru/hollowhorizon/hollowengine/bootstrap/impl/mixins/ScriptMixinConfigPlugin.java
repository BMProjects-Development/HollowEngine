package ru.hollowhorizon.hollowengine.bootstrap.impl.mixins;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import ru.hollowhorizon.hollowengine.bootstrap.impl.BootstrapRuntimeManager;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimeBridge;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins.ScriptMixinProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Companion plugin of the mixin config that carries the mixins of {@code .mixin.kts} scripts.
 * <p>
 * The config itself lists nothing. When Mixin asks for extra mixins, the runtime hands over the compiled
 * spec of every mixin script, the classes are generated from them, and each platform makes those bytes
 * reachable the way its class loading allows.
 */
public abstract class ScriptMixinConfigPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger("HollowEngineScriptMixins");

    private String mixinPackage = "";

    /** Tells the runtime which platform, mappings and side it runs on, as the mod entrypoint would later. */
    protected abstract void configureRuntime(RuntimeBridge bridge);

    protected abstract boolean isPhysicalClient();

    /** Makes the generated classes, keyed by internal name, readable by Mixin. */
    protected abstract void provide(Map<String, byte[]> classes) throws Exception;

    @Override
    public void onLoad(String mixinPackage) {
        this.mixinPackage = mixinPackage.endsWith(".") ? mixinPackage : mixinPackage + ".";
    }

    @Override
    public List<String> getMixins() {
        try {
            Map<String, byte[]> classes = generate();
            if (classes.isEmpty()) return List.of();
            provide(classes);

            List<String> names = new ArrayList<>();
            String prefix = mixinPackage.replace('.', '/');
            for (String name : classes.keySet()) {
                names.add(name.substring(prefix.length()).replace('/', '.'));
            }
            LOGGER.info("Generated {} mixin classes from scripts", names.size());
            return names;
        } catch (Throwable throwable) {
            LOGGER.error("Failed to prepare the mixins of scripts; none of them are applied", throwable);
            return List.of();
        }
    }

    private Map<String, byte[]> generate() {
        RuntimeBridge bridge = BootstrapRuntimeManager.bridge();
        configureRuntime(bridge);
        ScriptMixinProvider provider = BootstrapRuntimeManager.scriptMixinProvider();
        Map<String, byte[]> specs = BootstrapRuntimeManager.inRuntimeContext(provider::collect);

        boolean client = isPhysicalClient();
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : specs.entrySet()) {
            String scriptId = entry.getKey();
            try {
                ScriptMixinSpecReader.Spec spec = ScriptMixinSpecReader.read(scriptId, entry.getValue());
                if (spec.clientOnly() && !client) continue;
                classes.putAll(ScriptMixinGenerator.generate(mixinPackage, className(scriptId), spec.handlers()));
            } catch (Exception exception) {
                LOGGER.error("Skipping the mixins of script '{}'", scriptId, exception);
            }
        }
        return classes;
    }

    /** A class name that still says which script the mixin came from when Mixin reports a failure. */
    private static String className(String scriptId) {
        StringBuilder name = new StringBuilder("Script_");
        for (char character : scriptId.toCharArray()) {
            name.append(Character.isJavaIdentifierPart(character) ? character : '_');
        }
        return name.toString();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
