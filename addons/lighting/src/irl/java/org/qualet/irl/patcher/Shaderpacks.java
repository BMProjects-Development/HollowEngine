package org.qualet.irl.patcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Thin wrapper over the host's shaderpack directory + listing, with a direct-scan fallback. */
public final class Shaderpacks
{
    private static final Logger LOG = LoggerFactory.getLogger("irl-patcher");

    private Shaderpacks()
    {}

    public static Path dir()
    {
        PatcherHost host = Patcher.host();
        try
        {
            Path p = host.shaderpacksDir();
            if (p != null)
            {
                return p;
            }
        }
        catch (Throwable t)
        {
            LOG.warn("host.shaderpacksDir failed: {}", t.toString());
        }

        return host.gameDir().resolve("shaderpacks");
    }

    public static List<String> list()
    {
        Set<String> names = new LinkedHashSet<>();

        try
        {
            names.addAll(Patcher.host().listShaderpacks());
        }
        catch (Throwable t)
        {
            LOG.warn("host.listShaderpacks failed: {}", t.toString());
        }

        if (names.isEmpty())
        {
            Path dir = dir();
            try (Stream<Path> stream = Files.list(dir))
            {
                stream.forEach(p ->
                {
                    String name = p.getFileName().toString();
                    if (Files.isDirectory(p) || name.toLowerCase().endsWith(".zip"))
                    {
                        names.add(name);
                    }
                });
            }
            catch (Throwable t)
            {
                LOG.warn("Shaderpack dir scan failed for {}: {}", dir, t.toString());
            }
        }

        LOG.info("Shaderpacks: dir={} count={}", dir(), names.size());
        return new ArrayList<>(names);
    }

    public static Path packPath(String name)
    {
        return dir().resolve(name);
    }

    public static void openFolder()
    {
        Patcher.host().openFolder(dir());
    }

    /** "Photon_v1.2.zip" matches target "Photon": lowercase, alphanumerics only, substring. */
    public static boolean packMatchesTarget(String pack, String target)
    {
        String p = norm(pack);
        String t = norm(target);
        return t.isEmpty() || p.contains(t);
    }

    public static String norm(String s)
    {
        String lower = s.toLowerCase();
        if (lower.endsWith(".zip"))
        {
            lower = lower.substring(0, lower.length() - 4);
        }
        return lower.replaceAll("[^a-z0-9]", "");
    }

    /** The output pack name for a patch run: base name + "_IRLights" (+ "+DOF" for DoF-combo
     *  patches), with an optional "_2", "_3", ... suffix when {@code createNew} is set and the
     *  base name is already taken. */
    public static String outputName(IrlPatch patch, String packName, boolean createNew)
    {
        String base = packName;
        if (base.toLowerCase().endsWith(".zip"))
        {
            base = base.substring(0, base.length() - 4);
        }
        base = base + "_IRLights" + (patch.dof ? "+DOF" : "");

        if (!createNew)
        {
            return base;
        }

        if (!Files.exists(packPath(base)))
        {
            return base;
        }
        for (int i = 2; i < 1000; i++)
        {
            String candidate = base + "_" + i;
            if (!Files.exists(packPath(candidate)))
            {
                return candidate;
            }
        }
        return base;
    }
}
