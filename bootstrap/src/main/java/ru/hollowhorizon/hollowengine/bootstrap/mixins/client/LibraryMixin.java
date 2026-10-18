package ru.hollowhorizon.hollowengine.bootstrap.mixins.client;

import com.mojang.blaze3d.audio.Library;
import org.lwjgl.BufferUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.nio.IntBuffer;

/**
 * Asks the sound device for more effect sends per sound than the two it gives by default, so a sound can be
 * sent into several effects at once, as a reverb and an echo. A device without effects ignores the request.
 */
@Mixin(Library.class)
public class LibraryMixin {
    private static final int ALC_MAX_AUXILIARY_SENDS = 0x20003;
    private static final int REQUESTED_SENDS = 4;

    @ModifyArg(
            method = "init",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/openal/ALC10;alcCreateContext(JLjava/nio/IntBuffer;)J"),
            index = 1
    )
    private IntBuffer hollowengine$requestSends(IntBuffer attributes) {
        int length = 0;
        if (attributes != null) {
            while (attributes.get(attributes.position() + length) != 0) length += 2;
        }
        IntBuffer extended = BufferUtils.createIntBuffer(length + 3);
        for (int i = 0; i < length; i++) extended.put(attributes.get(attributes.position() + i));
        extended.put(ALC_MAX_AUXILIARY_SENDS).put(REQUESTED_SENDS).put(0).flip();
        return extended;
    }
}
