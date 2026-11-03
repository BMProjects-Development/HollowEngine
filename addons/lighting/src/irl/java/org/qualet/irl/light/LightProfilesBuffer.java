package org.qualet.irl.light;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

/** Appended to SSBO7 without moving any legacy light or header field. */
final class LightProfilesBuffer
{
    static final int MAGIC = 0x49524C50;
    static final int OFFSET = 16 + LightBuffer.MAX_LIGHTS * 96;
    static final int TARGET_OFFSET = OFFSET + LightBuffer.MAX_LIGHTS * LightProfile.BYTES;
    private static ByteBuffer profiles, targets;
    private static int gpuBytes;
    private static final LightProfile DEFAULT = new LightProfile();

    static void begin()
    {
        if (profiles == null) profiles = MemoryUtil.memAlloc(LightBuffer.MAX_LIGHTS * LightProfile.BYTES);
        if (targets == null) targets = MemoryUtil.memAlloc(4096);
        profiles.clear(); targets.clear();
    }

    static void add(LightProfile profile)
    {
        LightProfile p = profile == null ? DEFAULT : profile;
        p.write(profiles, targets.position()/4);
        if (p.selectedReplays) addTargets(p.replayIds);
        if (p.selectedLightReplays) addTargets(p.lightReplayIds);
    }

    private static void addTargets(int[] ids)
    {
        int required = Math.addExact(targets.position(), Math.multiplyExact(ids.length, 4));
        if (required > targets.capacity()) targets = MemoryUtil.memRealloc(targets, Math.max(required, targets.capacity()*2));
        for (int id : ids) targets.putInt(id);
    }

    /** Called while SSBO7 is bound, before the legacy payload is uploaded. */
    static void prepare(int lightCount)
    {
        while (profiles.position() < lightCount * LightProfile.BYTES) add(null);
        int required = Math.addExact(TARGET_OFFSET, targets.position());
        if (gpuBytes < required)
        {
            gpuBytes = Math.max(required, TARGET_OFFSET + targets.capacity());
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, gpuBytes, GL15.GL_DYNAMIC_DRAW);
        }
        profiles.flip(); targets.flip();
        if (profiles.hasRemaining()) GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, OFFSET, profiles);
        if (targets.hasRemaining()) GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, TARGET_OFFSET, targets);
    }

    static void delete()
    {
        if (profiles != null) MemoryUtil.memFree(profiles);
        if (targets != null) MemoryUtil.memFree(targets);
        profiles=null; targets=null; gpuBytes=0;
    }
}
