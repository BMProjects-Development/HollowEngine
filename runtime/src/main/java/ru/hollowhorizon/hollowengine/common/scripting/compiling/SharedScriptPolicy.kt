package ru.hollowhorizon.hollowengine.common.scripting.compiling

/** Shared bytecode must never link against per-importer classes, including implementation details. */
fun sharedScriptImportError(script: String, dependency: String): String =
    "Shared script '$script' imports non-shared script '$dependency'. " +
        "Add @file:SharedScript to the dependency; all transitive imports must be shared as well."
