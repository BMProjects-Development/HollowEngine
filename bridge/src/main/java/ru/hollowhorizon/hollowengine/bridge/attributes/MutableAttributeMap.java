package ru.hollowhorizon.hollowengine.bridge.attributes;

/** Access to per-entity attributes without changing the defaults shared by its entity type. */
public interface MutableAttributeMap {
    MutableAttributeState hollowengineMutableAttributes();
}
