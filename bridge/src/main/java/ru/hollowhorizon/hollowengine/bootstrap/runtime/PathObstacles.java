package ru.hollowhorizon.hollowengine.bootstrap.runtime;

/**
 * The cells one mob's path search treats as blocked besides blocks: those that solid colliders and
 * blocking bodies of other entities fill.
 */
@FunctionalInterface
public interface PathObstacles {
    boolean blocks(int x, int y, int z);
}
