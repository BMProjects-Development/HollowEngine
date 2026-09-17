package ru.hollowhorizon.hollowengine.common.scripting

import ru.hollowhorizon.hollowengine.common.scripting.compiling.ScriptingCompiler
import ru.hollowhorizon.hollowengine.common.scripting.deobf.mappings.Mappings
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptingAnalyzer
import java.io.File

interface ScriptingEnvironmentInitializer {
    fun initialize(javaHome: File, classpath: List<File>, scriptTypes: List<ScriptClassProvider>, mappings: Mappings)

    fun rebuild()
}

interface ScriptingEnvironment {
    val javaHome: File
    val classpath: List<File>
    val mappings: Mappings

    val analyzer: ScriptingAnalyzer
    val compiler: ScriptingCompiler

    fun close()

    companion object {
        @Volatile
        private var current: ScriptingEnvironment? = null

        var INSTANCE: ScriptingEnvironment
            get() = current ?: error("Kotlin scripting environment is not available. Install the compiler addon to compile or analyze Kotlin scripts.")
            set(value) {
                current?.close()
                current = value
            }

        fun currentOrNull(): ScriptingEnvironment? = current

        fun isAvailable(): Boolean = current != null

        fun clear() {
            current?.close()
            current = null
        }
    }
}
