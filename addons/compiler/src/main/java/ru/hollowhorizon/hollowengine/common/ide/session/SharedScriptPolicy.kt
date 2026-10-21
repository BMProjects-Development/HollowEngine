package ru.hollowhorizon.hollowengine.common.ide.session

import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionProvider
import org.jetbrains.kotlin.scripting.resolve.KtFileScriptSource
import ru.hollowhorizon.hollowengine.common.scripting.annotations.SharedScript
import ru.hollowhorizon.hollowengine.common.scripting.compiling.isSharedScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration

internal fun KtFile.isSharedScript(): Boolean {
    val definition = ScriptDefinitionProvider.getInstance(project)?.findDefinition(KtFileScriptSource(this))
    if (definition?.compilationConfiguration?.get(ScriptCompilationConfiguration.isSharedScript) == true) return true
    val marker = SharedScript::class.qualifiedName!!
    val aliases = importDirectives.mapNotNull { directive ->
        directive.importPath?.takeIf { it.fqName.asString() == marker }
            ?.let { directive.aliasName ?: it.fqName.shortName().asString() }
    }.toMutableSet()
    val shadowed = importDirectives.any { directive ->
        val path = directive.importPath ?: return@any false
        !path.isAllUnder && (directive.aliasName ?: path.fqName.shortName().asString()) == "SharedScript" &&
            path.fqName.asString() != marker
    }
    if (!shadowed) aliases += "SharedScript"
    return annotationEntries.any { entry ->
        val name = entry.typeReference?.text?.replace("`", "")
        name == marker || name in aliases
    }
}
