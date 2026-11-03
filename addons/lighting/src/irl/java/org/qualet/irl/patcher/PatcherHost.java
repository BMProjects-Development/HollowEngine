package org.qualet.irl.patcher;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * Platform seam for the patcher. Everything that differs between the two consumers
 * — the BBS addon (IRLite) and the standalone editor (IRL-redactor) — lives behind
 * this interface, so the patcher core itself stays pure Java (no Minecraft, no Iris,
 * no BBS). Each mod installs an implementation once at client init via
 * {@link Patcher#install(PatcherHost)}.
 */
public interface PatcherHost
{
    /** Minecraft game directory (typically {@code FabricLoader.getInstance().getGameDir()}). */
    Path gameDir();

    /** Iris shaderpacks directory, or {@code null} to fall back to {@code gameDir()/shaderpacks}. */
    Path shaderpacksDir();

    /** Installed shaderpack names (e.g. via Iris). May be empty to trigger a directory scan. */
    List<String> listShaderpacks();

    /** Open a folder in the OS file manager (MC {@code Util} / BBS {@code UIUtils}). */
    void openFolder(Path dir);

    /** Subfolder under {@link #gameDir()} that holds user patches: {@code <gameDir>/<name>/patches}. */
    String patchesDirName();

    /** Names of patches bundled in the mod jar to auto-extract on first use. May be empty. */
    List<String> bundledPatches();

    /** Open a bundled patch resource by file name, or {@code null} if it is not present. */
    InputStream openBundledPatch(String name);
}
