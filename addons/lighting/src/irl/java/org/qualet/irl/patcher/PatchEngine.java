package org.qualet.irl.patcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dry-run core of the patcher: plays an {@link IrlPatch} against a pack root
 * (a folder, or a root inside an opened zip) entirely in memory, with the same
 * sequential op semantics the applier used to have on disk. Instead of
 * stopping at the first failed op it records EVERY failure, so porting a patch
 * to a newer pack version reports all broken anchors in one run. The applier
 * writes {@link #files} to disk only when {@link #errors} stays empty;
 * validation just discards them.
 *
 * Anchor matching is EOL-tolerant: a {@code \n} in an anchor matches
 * {@code \n}, {@code \r\n} or a lone {@code \r} in the file, so multi-line
 * anchors work no matter how the user's pack copy is checked out. Inserted
 * bodies are converted to the target file's dominant line ending; untouched
 * bytes are preserved exactly.
 */
final class PatchEngine
{
    /** Stamp file written into a patched pack's root; its presence blocks re-patching. */
    static final String MARKER_FILE = "irlite_patched.txt";

    static final class FileState
    {
        String content;     // raw text, original line endings preserved
        String eol;         // dominant line ending, used for inserted text
        boolean dirty;      // actually modified (loaded-but-unchanged files are not written)
    }

    /** Touched files by pack-relative path, in op order; the applier writes the dirty ones. */
    final Map<String, FileState> files = new LinkedHashMap<>();
    final List<String> errors = new ArrayList<>();
    final List<String> infos = new ArrayList<>();
    /** The {@link PatchResult.Outcome} of the FIRST error added to {@link #errors}, if any. */
    PatchResult.Outcome firstErrorOutcome;
    int applied;
    int skipped;

    private PatchEngine()
    {}

    boolean ok()
    {
        return this.errors.isEmpty();
    }

    private void addError(PatchResult.Outcome outcome, String message)
    {
        if (this.errors.isEmpty())
        {
            this.firstErrorOutcome = outcome;
        }
        this.errors.add(message);
    }

    static PatchEngine run(Path root, IrlPatch patch)
    {
        PatchEngine engine = new PatchEngine();

        for (IrlPatch.Op op : patch.ops)
        {
            String path = op.file.replace('\\', '/');
            if (path.isBlank() || path.startsWith("/") || path.contains(":")
                || List.of(path.split("/")).contains(".."))
            {
                engine.addError(PatchResult.Outcome.BAD_SOURCE, "Patch path must stay inside the shaderpack: " + op.file);
            }
            else
            {
                Path target = root.resolve(path);
                Path existing = target;
                while (existing != null && !Files.exists(existing)) existing = existing.getParent();
                try
                {
                    if (existing != null && !existing.toRealPath().startsWith(root.toRealPath()))
                        engine.addError(PatchResult.Outcome.BAD_SOURCE, "Patch path escapes through a link: " + op.file);
                }
                catch (IOException failure)
                {
                    engine.addError(PatchResult.Outcome.READ_ERROR, failure.getMessage());
                }
            }
        }
        if (!engine.ok()) return engine;

        if (Files.exists(root.resolve(MARKER_FILE)))
        {
            engine.addError(PatchResult.Outcome.ALREADY_PATCHED,
                "pack is already patched (" + MARKER_FILE + " present) — patch a clean copy instead");
            return engine;
        }

        for (IrlPatch.Op op : patch.ops)
        {
            engine.applyOp(root, op);
        }

        return engine;
    }

    private void applyOp(Path root, IrlPatch.Op op)
    {
        if (op.kind == IrlPatch.Kind.ADD_FILE)
        {
            if (this.files.containsKey(op.file) || Files.exists(root.resolve(op.file)))
            {
                addError(PatchResult.Outcome.ADD_FILE_EXISTS,
                    "+file target already exists in pack: " + op.file + " (pack already patched?)");
                return;
            }

            FileState state = new FileState();
            state.content = op.body;
            state.eol = "\n";
            state.dirty = true;
            this.files.put(op.file, state);
            this.infos.add("+file " + op.file);
            this.applied++;
            return;
        }

        FileState state = this.files.get(op.file);
        if (state == null)
        {
            Path target = root.resolve(op.file);
            if (!Files.isRegularFile(target))
            {
                addError(PatchResult.Outcome.TARGET_NOT_FOUND, "target file not found in pack: " + op.file);
                return;
            }

            state = new FileState();
            try
            {
                state.content = Files.readString(target, StandardCharsets.UTF_8);
            }
            catch (IOException e)
            {
                addError(PatchResult.Outcome.READ_ERROR, "cannot read " + op.file + ": " + e.getMessage());
                return;
            }
            state.eol = detectEol(state.content);
            this.files.put(op.file, state);
        }

        // Try the anchor alternatives in order; the first with exactly one match wins.
        int[] span = null;
        String used = null;
        String ambiguous = null;
        int ambiguousCount = 0;

        for (String anchor : op.anchors)
        {
            List<int[]> matches = findMatches(state.content, anchor);
            if (matches.size() == 1)
            {
                span = matches.get(0);
                used = anchor;
                break;
            }
            if (matches.size() > 1 && ambiguous == null)
            {
                ambiguous = anchor;
                ambiguousCount = matches.size();
            }
        }

        if (span == null)
        {
            if (ambiguous != null)
            {
                addError(PatchResult.Outcome.ANCHOR_AMBIGUOUS,
                    "anchor is ambiguous (" + ambiguousCount + " matches) in " + op.file + ": \"" + preview(ambiguous) + "\"");
            }
            else if (op.optional)
            {
                this.skipped++;
                this.infos.add("skipped optional op (anchor not found) in " + op.file + ": \"" + preview(op.anchors.get(0)) + "\"");
            }
            else
            {
                addError(PatchResult.Outcome.ANCHOR_NOT_FOUND, "anchor not found in " + op.file + ": " + previews(op.anchors));
            }
            return;
        }

        String body = state.eol.equals("\n") ? op.body : op.body.replace("\n", state.eol);
        String content = state.content;

        switch (op.kind)
        {
            case AFTER:
                state.content = content.substring(0, span[1]) + state.eol + body + content.substring(span[1]);
                this.infos.add("after \"" + preview(used) + "\" in " + op.file);
                break;
            case BEFORE:
                state.content = content.substring(0, span[0]) + body + state.eol + content.substring(span[0]);
                this.infos.add("before \"" + preview(used) + "\" in " + op.file);
                break;
            case REPLACE:
            default:
                state.content = content.substring(0, span[0]) + body + content.substring(span[1]);
                this.infos.add("replace \"" + preview(used) + "\" in " + op.file);
                break;
        }

        state.dirty = true;
        this.applied++;
    }

    /**
     * Non-overlapping matches of {@code needle} in {@code hay} as
     * {@code [start, end)} raw spans. A {@code \n} in the needle (anchors are
     * LF-normalized by the parser) matches {@code \r\n}, {@code \n} or a lone
     * {@code \r} in the file.
     */
    static List<int[]> findMatches(String hay, String needle)
    {
        List<int[]> out = new ArrayList<>();
        if (needle.isEmpty())
        {
            return out;
        }

        int i = 0;
        while (i < hay.length())
        {
            int end = matchAt(hay, needle, i);
            if (end >= 0)
            {
                out.add(new int[] {i, end});
                i = end;
            }
            else
            {
                i++;
            }
        }
        return out;
    }

    /** Attempts a match of {@code needle} at {@code start}; returns the end index or -1. */
    private static int matchAt(String hay, String needle, int start)
    {
        int k = start;
        for (int j = 0; j < needle.length(); j++)
        {
            char c = needle.charAt(j);
            if (c == '\n')
            {
                if (k + 1 < hay.length() && hay.charAt(k) == '\r' && hay.charAt(k + 1) == '\n')
                {
                    k += 2;
                }
                else if (k < hay.length() && (hay.charAt(k) == '\n' || hay.charAt(k) == '\r'))
                {
                    k++;
                }
                else
                {
                    return -1;
                }
            }
            else
            {
                if (k < hay.length() && hay.charAt(k) == c)
                {
                    k++;
                }
                else
                {
                    return -1;
                }
            }
        }
        return k;
    }

    /** The file's dominant line ending; inserted text adopts it. New/empty files default to LF. */
    static String detectEol(String content)
    {
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < content.length(); i++)
        {
            if (content.charAt(i) == '\n')
            {
                if (i > 0 && content.charAt(i - 1) == '\r')
                {
                    crlf++;
                }
                else
                {
                    lf++;
                }
            }
        }
        return crlf > lf ? "\r\n" : "\n";
    }

    private static String previews(List<String> anchors)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < anchors.size(); i++)
        {
            if (i > 0)
            {
                sb.append(" | ");
            }
            sb.append('"').append(preview(anchors.get(i))).append('"');
        }
        return sb.toString();
    }

    static String preview(String s)
    {
        String oneLine = s.replace("\n", "\\n");
        return oneLine.length() > 60 ? oneLine.substring(0, 57) + "..." : oneLine;
    }
}
