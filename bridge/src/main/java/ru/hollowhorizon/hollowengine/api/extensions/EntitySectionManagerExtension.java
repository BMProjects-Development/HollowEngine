package ru.hollowhorizon.hollowengine.api.extensions;

import java.util.function.LongConsumer;

/** Lets a level hear when the entities of one of its chunks have finished loading. */
public interface EntitySectionManagerExtension {
    void hollowengine$setChunkEntitiesLoadedListener(LongConsumer listener);
}
