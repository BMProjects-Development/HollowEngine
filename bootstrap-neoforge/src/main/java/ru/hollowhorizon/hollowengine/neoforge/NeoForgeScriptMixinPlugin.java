package ru.hollowhorizon.hollowengine.neoforge;

import net.neoforged.fml.loading.FMLEnvironment;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import ru.hollowhorizon.hollowengine.bootstrap.impl.mixins.ScriptMixinConfigPlugin;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimeBridge;
import ru.hollowhorizon.hollowengine.bootstrap.runtime.RuntimePlatform;
import ru.hollowhorizon.hollowengine.neoforge.internal.NeoForgeModList;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.*;
import java.util.HashMap;
import java.util.Map;

/**
 * ModLauncher only reads classes out of the modules it was started with, and no module can gain classes
 * afterward. When a mixin class is not found there, Mixin falls back to the context classloader, so the
 * generated classes are served from a loader put in front of it until Mixin has read them.
 */
public final class NeoForgeScriptMixinPlugin extends ScriptMixinConfigPlugin {
    private static Thread servedThread;
    private static ClassLoader replacedLoader;

    /**
     * Puts the original context classloader back. Safe to call any number of times.
     */
    public static synchronized void restore() {
        if (servedThread == null) return;
        if (servedThread.getContextClassLoader() instanceof GeneratedClassLoader) {
            servedThread.setContextClassLoader(replacedLoader);
        }
        servedThread = null;
        replacedLoader = null;
    }

    @Override
    protected void configureRuntime(RuntimeBridge bridge) {
        bridge.setPlatform(RuntimePlatform.NEOFORGE);
        bridge.setProduction(FMLEnvironment.production);
        bridge.setClient(isPhysicalClient());
        bridge.initModList(new NeoForgeModList());
    }

    @Override
    protected boolean isPhysicalClient() {
        return FMLEnvironment.dist.isClient();
    }

    @Override
    protected synchronized void provide(Map<String, byte[]> classes) {
        restore();
        Thread thread = Thread.currentThread();
        replacedLoader = thread.getContextClassLoader();
        servedThread = thread;
        thread.setContextClassLoader(new GeneratedClassLoader(replacedLoader, classes));
    }

    /**
     * Mixin reads every mixin class right after the plugin lists them, so the first application of
     * them means the loader is no longer needed.
     */
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        restore();
    }

    private static final class GeneratedClassLoader extends ClassLoader {
        private final Map<String, byte[]> resources = new HashMap<>();

        GeneratedClassLoader(ClassLoader parent, Map<String, byte[]> classes) {
            super(parent);
            classes.forEach((name, bytes) -> resources.put(name + ".class", bytes));
        }

        @Override
        public URL getResource(String name) {
            byte[] bytes = resources.get(name);
            if (bytes == null) return super.getResource(name);
            try {
                return URL.of(URI.create("hollowengine-mixin:/" + name), new BytesHandler(bytes));
            } catch (MalformedURLException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static final class BytesHandler extends URLStreamHandler {
        private final byte[] bytes;

        BytesHandler(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        protected URLConnection openConnection(URL url) {
            return new URLConnection(url) {
                @Override
                public void connect() {
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(bytes);
                }
            };
        }
    }
}
