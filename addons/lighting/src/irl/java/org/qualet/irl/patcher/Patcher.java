package org.qualet.irl.patcher;

/**
 * Holds the single {@link PatcherHost} for the running mod. Install once at client
 * init ({@code Patcher.install(new MyPatcherHost())}); the patcher's static helpers
 * ({@link PatchLibrary}, {@link Shaderpacks}) read it through {@link #host()}.
 */
public final class Patcher
{
    private static volatile PatcherHost host;

    private Patcher()
    {}

    public static void install(PatcherHost h)
    {
        host = h;
    }

    public static PatcherHost host()
    {
        PatcherHost h = host;
        if (h == null)
        {
            throw new IllegalStateException("PatcherHost not installed — call Patcher.install(...) at mod init");
        }
        return h;
    }
}
