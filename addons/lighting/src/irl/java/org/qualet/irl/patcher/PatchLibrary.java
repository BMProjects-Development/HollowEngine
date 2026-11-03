package org.qualet.irl.patcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** The folder where users drop .irlights files: {@code <gameDir>/<patchesDirName>/patches}. */
public final class PatchLibrary
{
    public static final String EXTENSION = ".irlights";

    private static final Logger LOG = LoggerFactory.getLogger("irl-patcher");

    private static volatile boolean extracted;

    private PatchLibrary()
    {}

    public static Path dir()
    {
        PatcherHost host = Patcher.host();
        Path dir = host.gameDir().resolve(host.patchesDirName()).resolve("patches");
        try
        {
            Files.createDirectories(dir);
        }
        catch (IOException ignored)
        {}
        extractBundled(dir, host);
        return dir;
    }

    public static List<Path> list()
    {
        List<Path> patches = new ArrayList<>();
        Path dir = dir();

        try (Stream<Path> stream = Files.list(dir))
        {
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(EXTENSION))
                .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                .forEach(patches::add);
        }
        catch (IOException ignored)
        {}

        return patches;
    }

    public static void openFolder()
    {
        Patcher.host().openFolder(dir());
    }

    /** Syncs the bundled patches (from the host) into {@code dir} so they track the mod jar.
     *  A bundled file is (re)written whenever its on-disk bytes differ from the jar's copy:
     *  this unpacks missing files and, crucially, refreshes stale ones left over from an older
     *  jar (e.g. a patch missing a newly added op) — keeping the canonical set in lockstep with
     *  the SSBO struct contract so an updated mod can't be defeated by leftover disk files.
     *  Runs at most once per session. User-authored patches use their own file names (never in
     *  {@link PatcherHost#bundledPatches()}) and are untouched; to keep a hand-edited variant of
     *  a bundled patch, save it under a different name. */
    private static void extractBundled(Path dir, PatcherHost host)
    {
        if (extracted)
        {
            return;
        }
        extracted = true;

        for (String name : host.bundledPatches())
        {
            Path target = dir.resolve(name);
            try (InputStream in = host.openBundledPatch(name))
            {
                if (in == null)
                {
                    continue;
                }
                byte[] bundled = in.readAllBytes();
                byte[] current = Files.exists(target) ? Files.readAllBytes(target) : null;
                if (Arrays.equals(bundled, current))
                {
                    continue;
                }
                Files.write(target, bundled);
                LOG.info("{} bundled patch: {}", current == null ? "Unpacked" : "Refreshed", name);
            }
            catch (IOException e)
            {
                LOG.warn("Could not unpack bundled patch {}: {}", name, e.toString());
            }
        }
    }
}
