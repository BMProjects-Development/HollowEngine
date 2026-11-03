package org.qualet.irl.patcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the .irlights patch DSL.
 *
 * <pre>
 * # comment (only outside a &lt;&lt;&lt; ... &gt;&gt;&gt; body)
 * @name        Human readable name
 * @target      ShaderpackName        # informational, matched against pack names in the UI
 * @packversion v2.1                  # informational: pack version the patch was authored against
 * @irlite      1                     # GLSL contract version this patch needs (optional)
 * @marker      IRLITE
 *
 * +file path/relative/to/pack/new.glsl
 * &lt;&lt;&lt;
 * ...whole file content...
 * &gt;&gt;&gt;
 *
 * @file path/relative/to/pack/existing.glsl
 * after "literal anchor"
 * &lt;&lt;&lt;
 * ...inserted text...
 * &gt;&gt;&gt;
 * before "literal anchor"
 * &lt;&lt;&lt;
 * ...inserted text...
 * &gt;&gt;&gt;
 * replace "literal to replace"
 * &lt;&lt;&lt;
 * ...replacement...
 * &gt;&gt;&gt;
 * </pre>
 *
 * Anchors are literal substrings (with \\n, \\t, \\", \\\\ escapes). A body is
 * the raw text between a line that is exactly {@code <<<} and a line that is
 * exactly {@code >>>}.
 *
 * Two robustness extensions on the edit directives:
 * <ul>
 * <li>{@code after? "a"} (also {@code before?}/{@code replace?}) — an OPTIONAL
 *     op: skipped instead of failing when no anchor matches.</li>
 * <li>{@code after "a" | "b" | "c"} — anchor ALTERNATIVES, tried left to
 *     right; the first one that matches exactly once is used. Both forms
 *     combine: {@code replace? "a" | "b"}.</li>
 * </ul>
 */
public final class IrlPatchParser
{
    public static final class ParseException extends Exception
    {
        public ParseException(String message)
        {
            super(message);
        }
    }

    private final String[] lines;
    private int i;
    private String currentFile;

    private IrlPatchParser(String text)
    {
        this.lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
    }

    public static IrlPatch parse(String text) throws ParseException
    {
        return new IrlPatchParser(text).run();
    }

    private IrlPatch run() throws ParseException
    {
        IrlPatch patch = new IrlPatch();

        while (this.i < this.lines.length)
        {
            String raw = this.lines[this.i];
            String line = raw.trim();
            this.i++;

            if (line.isEmpty() || line.startsWith("#"))
            {
                continue;
            }

            if (line.startsWith("@name "))
            {
                patch.name = line.substring(6).trim();
            }
            else if (line.startsWith("@target "))
            {
                patch.target = line.substring(8).trim();
            }
            else if (line.startsWith("@packversion "))
            {
                patch.packVersion = line.substring(13).trim();
            }
            else if (line.startsWith("@irlite "))
            {
                String value = line.substring(8).trim();
                try
                {
                    patch.irliteVersion = Integer.parseInt(value);
                }
                catch (NumberFormatException e)
                {
                    throw new ParseException("line " + this.i + ": @irlite expects a number, got: " + value);
                }
            }
            else if (line.startsWith("@marker "))
            {
                patch.marker = line.substring(8).trim();
            }
            else if (line.equals("@dof") || line.startsWith("@dof "))
            {
                String value = line.length() > 4 ? line.substring(4).trim() : "";
                patch.dof = value.isEmpty()
                    || value.equals("1")
                    || value.equalsIgnoreCase("true")
                    || value.equalsIgnoreCase("yes");
            }
            else if (line.startsWith("+file "))
            {
                String path = normalize(line.substring(6).trim());
                String body = readBody();
                patch.ops.add(new IrlPatch.Op(IrlPatch.Kind.ADD_FILE, path, List.of(), body, false));
            }
            else if (line.startsWith("@file "))
            {
                this.currentFile = normalize(line.substring(6).trim());
            }
            else if (line.startsWith("after") || line.startsWith("before") || line.startsWith("replace"))
            {
                String word = word(line);
                boolean optional = word.endsWith("?");
                String bare = optional ? word.substring(0, word.length() - 1) : word;

                IrlPatch.Kind kind = bare.equals("after") ? IrlPatch.Kind.AFTER
                    : bare.equals("before") ? IrlPatch.Kind.BEFORE
                    : bare.equals("replace") ? IrlPatch.Kind.REPLACE
                    : null;

                if (kind == null)
                {
                    throw new ParseException("line " + this.i + ": unrecognized directive: " + line);
                }
                if (this.currentFile == null)
                {
                    throw new ParseException("line " + this.i + ": '" + word + "' before any @file");
                }

                List<String> anchors = parseAnchors(line.substring(word.length()).trim());
                String body = readBody();
                patch.ops.add(new IrlPatch.Op(kind, this.currentFile, anchors, body, optional));
            }
            else
            {
                throw new ParseException("line " + this.i + ": unrecognized directive: " + line);
            }
        }

        if (patch.ops.isEmpty())
        {
            throw new ParseException("patch has no operations");
        }

        return patch;
    }

    /** Reads a {@code <<<} ... {@code >>>} block; returns the inner text. */
    private String readBody() throws ParseException
    {
        // Skip blank / comment lines until the opening fence.
        while (this.i < this.lines.length)
        {
            String t = this.lines[this.i].trim();
            if (t.isEmpty() || t.startsWith("#"))
            {
                this.i++;
                continue;
            }
            break;
        }

        if (this.i >= this.lines.length || !this.lines[this.i].trim().equals("<<<"))
        {
            throw new ParseException("line " + (this.i + 1) + ": expected '<<<' body opener");
        }
        this.i++;

        StringBuilder body = new StringBuilder();
        boolean first = true;

        while (this.i < this.lines.length)
        {
            String raw = this.lines[this.i];
            if (raw.trim().equals(">>>"))
            {
                this.i++;
                return body.toString();
            }

            if (!first)
            {
                body.append('\n');
            }
            body.append(raw);
            first = false;
            this.i++;
        }

        throw new ParseException("unterminated body (missing '>>>')");
    }

    /** Parses {@code "a"} or {@code "a" | "b" | ...} into the anchor alternatives list. */
    private List<String> parseAnchors(String s) throws ParseException
    {
        List<String> anchors = new ArrayList<>();
        int pos = 0;

        while (true)
        {
            if (pos >= s.length() || s.charAt(pos) != '"')
            {
                throw new ParseException("line " + this.i + ": anchor must be in double quotes: " + s);
            }

            StringBuilder out = new StringBuilder();
            int k = pos + 1;
            boolean closed = false;

            while (k < s.length())
            {
                char c = s.charAt(k);
                if (c == '\\' && k + 1 < s.length())
                {
                    char n = s.charAt(++k);
                    switch (n)
                    {
                        case 'n': out.append('\n'); break;
                        case 't': out.append('\t'); break;
                        case '"': out.append('"'); break;
                        case '\\': out.append('\\'); break;
                        default: out.append(n); break;
                    }
                }
                else if (c == '"')
                {
                    closed = true;
                    break;
                }
                else
                {
                    out.append(c);
                }
                k++;
            }

            if (!closed)
            {
                throw new ParseException("line " + this.i + ": unterminated anchor quote: " + s);
            }
            if (out.length() == 0)
            {
                throw new ParseException("line " + this.i + ": empty anchor");
            }

            anchors.add(out.toString());
            pos = k + 1;

            while (pos < s.length() && s.charAt(pos) == ' ')
            {
                pos++;
            }
            if (pos >= s.length())
            {
                return anchors;
            }
            if (s.charAt(pos) != '|')
            {
                throw new ParseException("line " + this.i + ": expected '|' between anchor alternatives: " + s);
            }
            pos++;
            while (pos < s.length() && s.charAt(pos) == ' ')
            {
                pos++;
            }
        }
    }

    private static String normalize(String path)
    {
        return path.replace('\\', '/').replaceAll("^/+", "");
    }

    private static String word(String line)
    {
        int sp = line.indexOf(' ');
        return sp < 0 ? line : line.substring(0, sp);
    }
}
