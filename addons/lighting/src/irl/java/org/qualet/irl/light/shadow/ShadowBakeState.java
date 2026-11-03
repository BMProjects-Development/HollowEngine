package org.qualet.irl.light.shadow;

/**
 * Set true while the shadow baker is rendering caster form-trees (model blocks,
 * replays) into a depth FBO. Light form renderers check this to skip light
 * registration during the bake — otherwise a light form inside a caster's tree
 * would re-register every face/tile pass.
 */
public final class ShadowBakeState
{
    private static boolean baking = false;

    private ShadowBakeState()
    {}

    public static void setBaking(boolean value)
    {
        baking = value;
    }

    public static boolean isBaking()
    {
        return baking;
    }
}
