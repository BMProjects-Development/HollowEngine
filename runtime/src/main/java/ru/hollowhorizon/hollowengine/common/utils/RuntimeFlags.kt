package ru.hollowhorizon.hollowengine.common.utils

/**
 * What the game runs as, kept apart from anything that names a Minecraft class.
 */
object RuntimeFlags {
    @Volatile
    @JvmStatic
    var production: Boolean = false

    @Volatile
    @JvmStatic
    var physicalClient: Boolean = false

    @Volatile
    @JvmStatic
    var preparingMixins: Boolean = false
}
