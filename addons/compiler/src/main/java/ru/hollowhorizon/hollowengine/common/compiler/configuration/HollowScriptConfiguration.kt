package ru.hollowhorizon.hollowengine.common.compiler.configuration

import ru.hollowhorizon.hollowengine.common.scripting.annotations.Attach
import ru.hollowhorizon.hollowengine.common.scripting.annotations.ClientSide
import ru.hollowhorizon.hollowengine.common.scripting.annotations.Import
import ru.hollowhorizon.hollowengine.common.scripting.annotations.ServerSide
import ru.hollowhorizon.hollowengine.common.scripting.annotations.SharedScript
import ru.hollowhorizon.hollowengine.common.utils.RuntimeFlags
import java.io.File
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath

open class HollowScriptConfiguration(classpath: List<File>, body: Builder.() -> Unit = {}) : ScriptCompilationConfiguration({
    body()

    jvm {
        compilerOptions(
            "-opt-in=kotlin.time.ExperimentalTime,kotlin.ExperimentalStdlibApi",
            "-opt-in=org.jetbrains.kotlin.analysis.api.KaPlatformInterface",
            "-opt-in=org.jetbrains.kotlin.analysis.api.KaImplementationDetail",
            "-opt-in=org.jetbrains.kotlin.analysis.api.KaExperimentalApi",
            "-opt-in=org.jetbrains.kotlin.analysis.api.KaIdeApi",
            "-opt-in=org.jetbrains.kotlin.analysis.api.KaContextParameterApi",
            "-Xcollection-literals",
            "-jvm-target=21",
            "-Xadd-modules=ALL-MODULE-PATH" // Loading kotlin from shadowed jar
        )

        updateClasspath(classpath)
        if (!RuntimeFlags.production) dependenciesFromCurrentContext(wholeClasspath = true)
    }

    defaultImports(Import::class)
    defaultImports(Attach::class)
    defaultImports(SharedScript::class)
    defaultImports(ClientSide::class, ServerSide::class)

    refineConfiguration {
        onAnnotations(Import::class, handler = HollowScriptConfigurator())
        onAnnotations(Attach::class, handler = AttachConfigurator())
        onAnnotations(SharedScript::class, handler = SharedScriptConfigurator())
        onAnnotations(ClientSide::class, ServerSide::class, handler = ScriptSideConfigurator())
    }

    ide { acceptedLocations(ScriptAcceptedLocation.Everywhere) }

})
