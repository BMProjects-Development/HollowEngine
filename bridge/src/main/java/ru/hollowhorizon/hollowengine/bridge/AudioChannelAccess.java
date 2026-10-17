package ru.hollowhorizon.hollowengine.bridge;

/** Accessed only on Minecraft's sound executor, while this channel is owned by a playback. */
public interface AudioChannelAccess {
    int hollowengine$getSource();
}
