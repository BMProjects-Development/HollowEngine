package ru.hollowhorizon.hollowengine.runtime.remap

/** Moves whole packages and leaves every member name alone. */
class RelocationRemapper(private val relocation: PrefixRelocation) : TrackingRemapper() {
    override fun map(internalName: String): String = track(internalName, relocation.relocate(internalName))
}
