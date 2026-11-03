package org.qualet.irl.patcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed representation of an .irlights patch file. See {@link IrlPatchParser} for the
 * DSL grammar.
 */
public final class IrlPatch
{
    /**
     * Version of the GLSL contract this build provides (SSBO layout, sampler
     * names). A patch may pin the contract it was written against via
     * {@code @irlite N}; the applier refuses a mismatch instead of producing a
     * pack that compiles against the wrong layout.
     */
    public static final int CONTRACT_VERSION = 1;

    public enum Kind
    {
        ADD_FILE,   // create a new file (body = whole content)
        AFTER,      // insert body right after the anchor
        BEFORE,     // insert body right before the anchor
        REPLACE     // replace the anchor literal with body
    }

    public static final class Op
    {
        public final Kind kind;
        public final String file;
        public final List<String> anchors;  // alternatives, tried in order; empty for ADD_FILE
        public final String body;
        public final boolean optional;      // skip (instead of fail) when no anchor matches

        public Op(Kind kind, String file, List<String> anchors, String body, boolean optional)
        {
            this.kind = kind;
            this.file = file;
            this.anchors = anchors;
            this.body = body;
            this.optional = optional;
        }
    }

    public String name = "";
    public String target = "";
    public String packVersion = "";     // informational: pack version the patch was authored against
    public String marker = "IRLITE";
    public int irliteVersion = 0;       // 0 = patch does not pin a contract version
    public boolean dof = false;         // combined lights+DOF combo: host appends "+DOF" to the patched pack name
    public final List<Op> ops = new ArrayList<>();
}
