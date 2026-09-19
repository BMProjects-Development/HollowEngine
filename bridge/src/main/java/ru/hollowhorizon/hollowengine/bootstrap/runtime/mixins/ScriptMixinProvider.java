package ru.hollowhorizon.hollowengine.bootstrap.runtime.mixins;

import java.util.Map;

/**
 * Hands the bootstrap the mixin scripts of this launch while mixins are being prepared.
 * <p>
 * It runs before any game class is loaded and must not load one itself: a class loaded at that moment
 * is loaded without its mixins, and one that has mixins pending is an error.
 */
public interface ScriptMixinProvider {
    /** The {@link ScriptMixinSpec} class of every mixin script that applies to this side, keyed by script id. */
    Map<String, byte[]> collect();
}
