package ru.hollowhorizon.hollowengine.bootstrap.mixins.client;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import ru.hollowhorizon.hollowengine.bridge.AudioChannelAccess;

@Mixin(Channel.class)
public interface ChannelAccessor extends AudioChannelAccess {
    @Override @Accessor("source")
    int hollowengine$getSource();
}
