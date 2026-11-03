package org.qualet.irl.light.shadow;

/** Complete, render-evaluated silhouette state. Together the domains must cover
 * every depth/cutout change. A backend sampling final vertices may conservatively
 * combine pose, morph and geometry in its evaluated-output domain. Zero is a
 * valid revision; UNKNOWN is explicit.
 * A known revision promises the same state for every shadow draw in this bake,
 * including off-screen casters. It does not promote a caster to the static layer. */
public record CasterRevision(boolean known, long transform, long pose, long morph,
                             long geometry, long material, long resources)
{
    public static final CasterRevision UNKNOWN = new CasterRevision(false, 0, 0, 0, 0, 0, 0);
}
