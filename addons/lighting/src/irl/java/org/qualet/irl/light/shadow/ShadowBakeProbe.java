package org.qualet.irl.light.shadow;

/**
 * Dev-profiler seam for the shadow bake ({@link ShadowBaker}). The host mod may
 * install one via {@link ShadowEngine#installBakeProbe} (IRLite does, only under
 * its {@code -Dirlite.profileVl=true} dev flag); with none installed every call
 * site is a null-check no-op, so production frames pay nothing.
 *
 * <p>{@link #section} fires at the seams of {@code bakeInner} — spot loop, spot
 * pyramid/EVSM flush, point loop, point pyramid/EVSM flush, tail — and is meant
 * to SWITCH a GPU timer bracket (close current, open named): the caller holds a
 * single non-nestable GL_TIME_ELAPSED bracket around the whole bake, so the
 * sections partition it into sibling brackets rather than nesting inside it.
 *
 * <p>{@link #counter} accumulates named per-window work counts (full static
 * bakes per type+tier, overlay draws, static->live copies, faces baked/copied,
 * pyramid/EVSM dirty flush sizes) into whatever sink the host installs.
 */
public interface ShadowBakeProbe
{
    /** Close the current GPU timer bracket and open a sibling named {@code name}. */
    void section(String name);

    /** Add {@code amount} to the window counter {@code key}. */
    void counter(String key, int amount);

    /** Opt in to repeated copy/draw segments; consumers must sum them per frame. */
    default boolean detailedTimings()
    {
        return false;
    }
}
