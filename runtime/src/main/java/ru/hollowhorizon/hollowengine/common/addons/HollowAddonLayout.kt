package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.common.scripting.source.AddonScriptSource
import java.util.jar.JarFile

internal object HollowAddonLayout {
    const val FORMAT_ATTRIBUTE = "HollowEngine-Addon-Format"
    const val CURRENT_FORMAT = "3"

    const val CLASSES_JAR = "META-INF/hollowengine/classes.jar"
    const val REMAP_TABLE = "META-INF/hollowengine/remap-fabric.tbl.gz"
    const val SOURCE_PREFIX = AddonScriptSource.SOURCE_PREFIX
    const val COMPILED_PREFIX = AddonScriptSource.COMPILED_PREFIX
    const val ASSETS_PREFIX = "assets/"
    const val DATA_PREFIX = "data/"

    fun requireCurrentFormat(jar: JarFile) {
        val format = jar.manifest?.mainAttributes?.getValue(FORMAT_ATTRIBUTE)
        require(format == CURRENT_FORMAT) {
            if (format == null) "Not a HollowEngine addon jar: its manifest does not name a format"
            else "Addon format $format is no longer supported; rebuild the addon for format $CURRENT_FORMAT"
        }
    }
}
