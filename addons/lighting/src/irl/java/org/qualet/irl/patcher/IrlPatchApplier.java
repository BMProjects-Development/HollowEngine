package org.qualet.irl.patcher;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Applies an {@link IrlPatch} to a clean source shaderpack (a folder or a
 * .zip) producing a fresh output folder. Never touches the source.
 *
 * All ops are first played in memory by {@link PatchEngine} against the
 * source; nothing is written (and any existing output is left alone) unless
 * EVERY op resolves, and all failures are reported together. A successful
 * output gets a {@code irlite_patched.txt} marker stamped into its root, which
 * the engine refuses to patch again — patch a clean copy instead.
 */
public final class IrlPatchApplier
{
    private IrlPatchApplier()
    {}

    /** Checks the patch against the source pack without writing anything. */
    public static PatchResult validate(Path sourcePack, IrlPatch patch)
    {
        PatchResult result = new PatchResult();
        if (!checkContract(patch, result))
        {
            return result;
        }

        try (SourceRoot source = SourceRoot.open(sourcePack))
        {
            PatchEngine engine = PatchEngine.run(source.root, patch);
            report(engine, result);
            if (engine.ok())
            {
                result.outcome = PatchResult.Outcome.OK;
                result.summary = "Patch fits " + sourcePack.getFileName() + ": " + opCount(engine);
                result.info(result.summary);
            }
        }
        catch (PatchException e)
        {
            result.fail(PatchResult.Outcome.BAD_SOURCE, e.getMessage());
        }
        catch (IOException e)
        {
            result.fail(PatchResult.Outcome.IO_ERROR, "IO error: " + e.getMessage());
        }
        return result;
    }

    public static PatchResult apply(Path sourcePack, Path outputPack, IrlPatch patch)
    {
        PatchResult result = new PatchResult();
        if (!checkContract(patch, result))
        {
            return result;
        }

        try (SourceRoot source = SourceRoot.open(sourcePack))
        {
            Path destination = outputPack.toAbsolutePath().normalize();
            Path original = sourcePack.toRealPath();
            Path ancestor = destination;
            while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
            Path effectiveDestination = ancestor.toRealPath().resolve(ancestor.relativize(destination));
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) || effectiveDestination.startsWith(original))
            {
                result.fail(PatchResult.Outcome.BAD_SOURCE, "Output must be a new path outside the source pack");
                return result;
            }
            PatchEngine engine = PatchEngine.run(source.root, patch);
            report(engine, result);
            if (!engine.ok())
            {
                return result;
            }

            Files.createDirectories(destination.getParent());
            Files.createDirectory(destination);
            try
            {
                copyRecursive(source.root, outputPack);
                result.info("Copied pack -> " + outputPack.getFileName());

                for (Map.Entry<String, PatchEngine.FileState> entry : engine.files.entrySet())
                {
                    if (!entry.getValue().dirty)
                    {
                        continue;
                    }
                    Path target = outputPack.resolve(entry.getKey());
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, entry.getValue().content, StandardCharsets.UTF_8);
                }

                writeMarker(outputPack, patch);
            }
            catch (IOException e)
            {
                result.fail(PatchResult.Outcome.IO_ERROR, "IO error: " + e.getMessage());
                tryCleanup(outputPack, result);
                return result;
            }

            result.outcome = PatchResult.Outcome.OK;
            result.summary = "Patched OK -> " + outputPack.getFileName() + " (" + opCount(engine) + ")";
            result.info(result.summary);
        }
        catch (PatchException e)
        {
            result.fail(PatchResult.Outcome.BAD_SOURCE, e.getMessage());
        }
        catch (IOException e)
        {
            result.fail(PatchResult.Outcome.IO_ERROR, "IO error: " + e.getMessage());
        }
        return result;
    }

    /** A source pack root: the folder itself, or the shaders-containing root inside a zip. */
    private static final class SourceRoot implements Closeable
    {
        final Path root;
        private final FileSystem zip;

        private SourceRoot(Path root, FileSystem zip)
        {
            this.root = root;
            this.zip = zip;
        }

        static SourceRoot open(Path sourcePack) throws IOException, PatchException
        {
            if (sourcePack != null && Files.isDirectory(sourcePack))
            {
                if (!Files.isDirectory(sourcePack.resolve("shaders")))
                    throw new PatchException("source pack has no shaders/ folder: " + sourcePack);
                return new SourceRoot(sourcePack, null);
            }

            if (sourcePack != null && Files.isRegularFile(sourcePack)
                && sourcePack.getFileName().toString().toLowerCase().endsWith(".zip"))
            {
                FileSystem zip = FileSystems.newFileSystem(sourcePack, (ClassLoader) null);
                try
                {
                    return new SourceRoot(findZipRoot(zip, sourcePack), zip);
                }
                catch (PatchException | IOException e)
                {
                    zip.close();
                    throw e;
                }
            }

            throw new PatchException("source pack is not a folder or .zip: " + sourcePack);
        }

        /** The pack root is wherever shaders/ lives: the zip root, or a single top-level folder. */
        private static Path findZipRoot(FileSystem zip, Path sourcePack) throws IOException, PatchException
        {
            Path root = zip.getPath("/");
            if (Files.isDirectory(root.resolve("shaders")))
            {
                return root;
            }

            List<Path> candidates = new ArrayList<>();
            try (Stream<Path> top = Files.list(root))
            {
                for (Path dir : (Iterable<Path>) top::iterator)
                {
                    if (Files.isDirectory(dir) && Files.isDirectory(dir.resolve("shaders")))
                    {
                        candidates.add(dir);
                    }
                }
            }

            if (candidates.size() == 1)
            {
                return candidates.get(0);
            }
            throw new PatchException("no shaders/ folder found inside zip: " + sourcePack.getFileName());
        }

        @Override
        public void close() throws IOException
        {
            if (this.zip != null)
            {
                this.zip.close();
            }
        }
    }

    private static boolean checkContract(IrlPatch patch, PatchResult result)
    {
        if (patch.irliteVersion != 0 && patch.irliteVersion != IrlPatch.CONTRACT_VERSION)
        {
            result.fail(PatchResult.Outcome.CONTRACT_MISMATCH, "patch requires GLSL contract v" + patch.irliteVersion
                + ", this build provides v" + IrlPatch.CONTRACT_VERSION
                + " — update the " + (patch.irliteVersion < IrlPatch.CONTRACT_VERSION ? "patch" : "mod"));
            return false;
        }
        return true;
    }

    /** Copies the engine's log into the result; on failures, summarizes ALL of them. */
    private static void report(PatchEngine engine, PatchResult result)
    {
        for (String info : engine.infos)
        {
            result.info(info);
        }
        if (!engine.ok())
        {
            List<String> errors = engine.errors;
            result.fail(engine.firstErrorOutcome, errors.size() == 1
                ? errors.get(0)
                : errors.size() + " errors, first: " + errors.get(0));
            for (int i = 1; i < errors.size(); i++)
            {
                result.log.add("ERROR: " + errors.get(i));
            }
        }
    }

    private static String opCount(PatchEngine engine)
    {
        return engine.applied + " ops" + (engine.skipped > 0 ? ", " + engine.skipped + " skipped" : "");
    }

    private static void writeMarker(Path outputPack, IrlPatch patch) throws IOException
    {
        String text = "marker: " + patch.marker + "\n"
            + "patch: " + patch.name + "\n"
            + "target: " + patch.target + (patch.packVersion.isEmpty() ? "" : " " + patch.packVersion) + "\n"
            + "applied: " + LocalDate.now() + "\n"
            + "Written by the IRLights shader patcher. Its presence blocks re-patching this pack;\n"
            + "patch a clean copy of the original pack instead.\n";
        Files.writeString(outputPack.resolve(PatchEngine.MARKER_FILE), text, StandardCharsets.UTF_8);
    }

    private static void copyRecursive(Path source, Path dest) throws IOException
    {
        try (Stream<Path> walk = Files.walk(source))
        {
            for (Path src : (Iterable<Path>) walk::iterator)
            {
                Path rel = source.relativize(src);
                if (Files.isSymbolicLink(src)) throw new IOException("Shaderpack contains a symbolic link: " + rel);
                Path dst = dest.resolve(rel.toString());
                if (Files.isDirectory(src))
                {
                    Files.createDirectories(dst);
                }
                else
                {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst);
                }
            }
        }
    }

    /** Deletes the tree; fails loudly when something survives (e.g. locked by another program). */
    private static void deleteRecursive(Path path) throws IOException
    {
        List<Path> failed = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(path))
        {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator)
            {
                try
                {
                    Files.delete(p);
                }
                catch (IOException e)
                {
                    failed.add(p);
                }
            }
        }
        if (!failed.isEmpty())
        {
            throw new IOException("could not remove " + failed.size() + " file(s) of the old output"
                + " (locked by another program?), e.g. " + failed.get(0));
        }
    }

    private static void tryCleanup(Path outputPack, PatchResult result)
    {
        try
        {
            if (outputPack != null && Files.exists(outputPack))
            {
                deleteRecursive(outputPack);
                result.info("Rolled back partial output.");
            }
        }
        catch (IOException e)
        {
            result.log.add("Rollback incomplete: " + e.getMessage());
        }
    }

    private static final class PatchException extends Exception
    {
        PatchException(String message)
        {
            super(message);
        }
    }
}
