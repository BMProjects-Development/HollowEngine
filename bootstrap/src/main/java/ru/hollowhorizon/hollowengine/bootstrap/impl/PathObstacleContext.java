package ru.hollowhorizon.hollowengine.bootstrap.impl;

/** A path search context that knows which cells colliders fill; every {@code PathfindingContext} is one. */
public interface PathObstacleContext {
    boolean hollowengine$isObstacle(int x, int y, int z);
}
