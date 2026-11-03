package org.qualet.irl.patcher;

import java.util.ArrayList;
import java.util.List;

/** Outcome of applying a patch, with a human-readable log. */
public final class PatchResult
{
    /** Machine-readable classification of the result, set at the point the outcome is decided
     *  (as opposed to sniffing the English {@link #summary} after the fact). Hosts should switch
     *  on this instead of scanning {@code summary} for substrings. */
    public enum Outcome
    {
        /** Not yet decided — {@link #ok} and {@link #summary} haven't been populated. */
        PENDING,
        /** Patch applied (or, in a dry run, would apply) cleanly. */
        OK,
        /** The patch's GLSL contract version doesn't match this build's. */
        CONTRACT_MISMATCH,
        /** The source pack isn't a folder or .zip, or has no shaders/ folder. */
        BAD_SOURCE,
        /** The pack already carries the patcher's marker file. */
        ALREADY_PATCHED,
        /** An op's target file doesn't exist in the pack. */
        TARGET_NOT_FOUND,
        /** An op's target file (or the marker) couldn't be read from disk. */
        READ_ERROR,
        /** An op's anchor matched more than once. */
        ANCHOR_AMBIGUOUS,
        /** An op's anchor matched nowhere, and the op wasn't optional. */
        ANCHOR_NOT_FOUND,
        /** A +file op's target already exists in the pack. */
        ADD_FILE_EXISTS,
        /** Writing the output pack (or cleaning up an old one) hit an IO error. */
        IO_ERROR,
        /** Some other failure not covered above. */
        OTHER
    }

    public boolean ok = true;
    public String summary = "";
    public Outcome outcome = Outcome.PENDING;
    public final List<String> log = new ArrayList<>();

    public void info(String message)
    {
        this.log.add(message);
    }

    public void fail(String message)
    {
        this.ok = false;
        this.summary = message;
        this.log.add("ERROR: " + message);
    }

    public void fail(Outcome outcome, String message)
    {
        this.outcome = outcome;
        fail(message);
    }
}
