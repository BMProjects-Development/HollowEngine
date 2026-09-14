package ru.hollowhorizon.hollowengine.common.scripting

import kotlin.reflect.KClass

fun ScriptClassProvider(extension: String, baseClass: KClass<*>, defaultImports: List<KClass<*>>) =
    ScriptClassProvider(extension, baseClass.qualifiedName!!, defaultImports.map { it.qualifiedName!! })

data class ScriptClassProvider(
    val extension: String,
    val baseClass: String,
    val defaultImports: List<String> = emptyList(),
    val implicitReceivers: List<KClass<*>> = emptyList(),
    val clientSideReceivers: List<String>? = null,
)
