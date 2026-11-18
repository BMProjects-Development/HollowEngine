package ru.hollowhorizon.hollowengine.common.scripting.source

import java.io.File

/**
 * One namespace worth of scripts. The sandbox directory and every addon that ships scripts are the
 * same kind of thing here: a named container that can hand out sources, precompiled artifacts, the
 * classpath its scripts compile against, and the classloader they run in.
 */
interface ScriptSource {
    val namespace: String

    /** Parent classloader for compiled scripts of this namespace. */
    val classLoader: ClassLoader

    /** Classpath entries in the compiler's namespace, e.g. named addon classes and bundled libraries. */
    val classpath: List<File>

    /** Namespaces this one may reference from `@file:Import`. */
    val dependencies: List<String>

    /**
     * Identifies the content of this source as a whole. It participates in cache keys so that
     * replacing an addon jar invalidates everything compiled from it.
     */
    val fingerprint: String

    fun list(): List<ScriptId>

    fun read(id: ScriptId): ScriptArtifacts?
}

fun isScriptSourceFile(name: String): Boolean = name.endsWith(".kts") || name.endsWith(".story")

fun isCompilableScriptFile(name: String): Boolean = name.endsWith(".kts")

/**
 * What a source can provide for a single script. [sourceFile] is absent when an addon was built
 * without its sources, [precompiled] is absent for scripts that were never compiled ahead of time.
 *
 * [precompiledShared] is this script's classes as a `@file:SharedScript`, which the compiler keeps out
 * of every artifact importing it. It travels beside [precompiled] and is absent for anything not shared.
 */
data class ScriptArtifacts(
    val id: ScriptId,
    val sourceFile: File?,
    val precompiled: File?,
    val precompiledShared: File? = null,
) {
    init {
        require(sourceFile != null || precompiled != null) {
            "Script $id has neither a source file nor a precompiled artifact"
        }
    }
}
