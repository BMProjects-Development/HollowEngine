package ru.hollowhorizon.hollowengine.common.scene

/** Internal runtime bindings are notified in the same order as scene components. */
interface SceneObjectObserver {
    fun onActivityChanged(active: Boolean) = Unit
    fun onRemoved() = Unit
}
