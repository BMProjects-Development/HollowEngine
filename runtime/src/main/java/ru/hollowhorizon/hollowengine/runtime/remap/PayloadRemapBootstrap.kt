package ru.hollowhorizon.hollowengine.runtime.remap

import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptFingerprint
import java.io.File

/**
 * Entry point that bootstrap calls to rewrite the payload before loading it.
 */
object PayloadRemapBootstrap {
    @JvmStatic
    fun remap(payload: String, table: String, output: String) {
        val remapTable = PayloadRemapTable.read(File(table))
        val runtime = ScriptFingerprint.runtimeIdentity("fabric", remapTable.to, production = true)
        remapTable.applyTo(File(payload), File(output), artifactRuntime = runtime)
    }
}
