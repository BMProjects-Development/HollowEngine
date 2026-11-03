package org.qualet.irl.light;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Shared spotlight math used by every light collector / driver / guide across
 * the trilogy. Pure float arithmetic, no MC/BBS/Iris state — the same numbers
 * whether the caller is the BBS scanner, the standalone editor's driver, or a
 * guide renderer.
 *
 * <p>Two things were copied verbatim in half a dozen places before this class:
 * turning a full cone angle (degrees) into a half-angle cosine for the SSBO,
 * and normalizing a spot direction with a caller-chosen fallback for the
 * degenerate near-zero case. The fallback is a parameter on purpose: the
 * drivers default to +Z, the guides to -Y, and unifying that default silently
 * would change what a zero-length direction looks like — so every call site
 * keeps passing its own.</p>
 */
public final class LightMath
{
    /** Below this length a direction is treated as degenerate and the fallback wins. */
    private static final float DEGENERATE = 1e-4F;

    private LightMath()
    {}

    /**
     * Half-angle cosine of a full cone angle in degrees: {@code cos(rad(deg * 0.5))}.
     * This is the exact value the SSBO stores for a spot's outer/inner cutoff.
     */
    public static float coneCosHalf(float deg)
    {
        return (float) Math.cos(Math.toRadians(deg * 0.5F));
    }

    /**
     * Outer/inner half-angle cosines for a spot cone, with the inner angle
     * clamped to the outer so the inner cutoff never opens wider than the cone
     * ({@code inner = min(inner, outer)} on the ANGLE — a larger cosine, i.e. a
     * tighter or equal falloff start). Both fields are ready for the SSBO.
     */
    public static Cone cone(float outerDeg, float innerDeg)
    {
        float outer = coneCosHalf(outerDeg);
        float inner = coneCosHalf(Math.min(innerDeg, outerDeg));
        return new Cone(outer, inner);
    }

    /** Outer/inner half-angle cosines of a spot cone. */
    public record Cone(float cosOuter, float cosInner)
    {}

    /**
     * Normalizes {@code (x, y, z)} in place, falling back to {@code (fx, fy, fz)}
     * when the input is degenerate (near-zero length). The result overwrites the
     * first three components of {@code out}; its {@code w}, if any, is untouched.
     */
    public static Vector4f normalizeDir(float x, float y, float z, float fx, float fy, float fz, Vector4f out)
    {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len > DEGENERATE)
        {
            out.x = x / len;
            out.y = y / len;
            out.z = z / len;
        }
        else
        {
            out.x = fx;
            out.y = fy;
            out.z = fz;
        }
        return out;
    }

    /**
     * Normalizes {@code (x, y, z)} into {@code out}, falling back to the given
     * fallback when the input is degenerate.
     */
    public static Vector3f normalizeDir(float x, float y, float z, float fx, float fy, float fz, Vector3f out)
    {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len > DEGENERATE)
        {
            out.set(x / len, y / len, z / len);
        }
        else
        {
            out.set(fx, fy, fz);
        }
        return out;
    }

    /**
     * Normalizes {@code (x, y, z)} into {@code out[0..2]}, falling back to the
     * given fallback when the input is degenerate. Returns {@code out}.
     */
    public static float[] normalizeDir(float x, float y, float z, float fx, float fy, float fz, float[] out)
    {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len > DEGENERATE)
        {
            out[0] = x / len;
            out[1] = y / len;
            out[2] = z / len;
        }
        else
        {
            out[0] = fx;
            out[1] = fy;
            out[2] = fz;
        }
        return out;
    }

    /**
     * Normalizes {@code (x, y, z)} into a fresh {@link Vec3}, falling back to the
     * given fallback when the input is degenerate.
     */
    public static Vec3 normalizeDir(double x, double y, double z, double fx, double fy, double fz)
    {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len > DEGENERATE)
        {
            return new Vec3(x / len, y / len, z / len);
        }
        return new Vec3(fx, fy, fz);
    }
}
