package org.qualet.irl.light;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Optional per-source overrides. The first six vectors mirror VlGlobalsBuffer. */
public final class LightProfile
{
    public static final int BYTES = 112;
    public boolean customVl, customOutline, selectedReplays, selectedLightReplays;
    public float intensity = 1, maxDist = 96, tipBoost = 1.5F, tipRadius = 1.5F;
    public float noiseAmount = .6F, noiseScale = 2, noiseSpeed = .25F, noiseMorph;
    public int steps = 48, shadowStride = 2, noiseStride = 2, flags = 3 | 256 | 2048;
    public float strength = .65F, fresnel = 2.2F, back = 1, front = .3F, glow = .12F;
    public int pixelSize = 6;
    public int[] replayIds = new int[0];
    public int[] lightReplayIds = new int[0];

    public void copyFrom(LightProfile p)
    {
        customVl=p.customVl; customOutline=p.customOutline; selectedReplays=p.selectedReplays;
        selectedLightReplays=p.selectedLightReplays;
        intensity=p.intensity; maxDist=p.maxDist; tipBoost=p.tipBoost; tipRadius=p.tipRadius;
        noiseAmount=p.noiseAmount; noiseScale=p.noiseScale; noiseSpeed=p.noiseSpeed; noiseMorph=p.noiseMorph;
        steps=p.steps; shadowStride=p.shadowStride; noiseStride=p.noiseStride; flags=p.flags;
        strength=p.strength; fresnel=p.fresnel; back=p.back; front=p.front; glow=p.glow; pixelSize=p.pixelSize;
        if (!Arrays.equals(replayIds, p.replayIds)) replayIds = p.replayIds.clone();
        if (!Arrays.equals(lightReplayIds, p.lightReplayIds)) lightReplayIds = p.lightReplayIds.clone();
    }

    static float finite(float v, float min, float max, float fallback)
    {
        return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : fallback;
    }

    void write(ByteBuffer b, int targetOffset)
    {
        b.putFloat(finite(intensity,0,5,1)).putFloat(finite(maxDist,1,256,96))
            .putFloat(finite(tipBoost,0,4,1.5F)).putFloat(finite(tipRadius,.01F,4,1.5F));
        b.putFloat(finite(noiseAmount,0,1,.6F)).putFloat(finite(noiseScale,.01F,6,2))
            .putFloat(finite(noiseSpeed,0,3,.25F)).putFloat(0);
        b.putInt(Math.max(1,Math.min(96,steps))).putInt(Math.max(1,Math.min(8,shadowStride)))
            .putInt(Math.max(1,Math.min(8,noiseStride))).putInt(flags);
        b.putFloat(finite(noiseMorph,0,3,0)).putFloat(0).putFloat(0).putFloat(0);
        b.putFloat(finite(strength,0,3,.65F)).putFloat(finite(fresnel,.001F,4,2.2F))
            .putFloat(finite(back,0,2,1)).putFloat(finite(front,0,1.5F,.3F));
        b.putFloat(finite(glow,0,.75F,.12F)).putFloat(Math.max(1,Math.min(6,pixelSize))).putFloat(0).putFloat(0);
        b.putInt((customVl?1:0)|(customOutline?2:0)|(selectedReplays?4:0)|(selectedLightReplays?8:0))
            .putInt(targetOffset).putInt(selectedReplays ? replayIds.length : 0)
            .putInt(selectedLightReplays ? lightReplayIds.length : 0);
    }
}
