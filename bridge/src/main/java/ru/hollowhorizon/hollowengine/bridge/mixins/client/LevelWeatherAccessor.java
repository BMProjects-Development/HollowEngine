package ru.hollowhorizon.hollowengine.bridge.mixins.client;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Level.class)
public interface LevelWeatherAccessor {
    @Accessor("rainLevel")
    float hollowengine$getRainLevel();

    @Accessor("oRainLevel")
    float hollowengine$getPreviousRainLevel();

    @Accessor("oRainLevel")
    void hollowengine$setPreviousRainLevel(float level);

    @Accessor("thunderLevel")
    float hollowengine$getThunderLevel();

    @Accessor("oThunderLevel")
    float hollowengine$getPreviousThunderLevel();

    @Accessor("oThunderLevel")
    void hollowengine$setPreviousThunderLevel(float level);
}
