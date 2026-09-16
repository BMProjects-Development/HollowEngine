package ru.hollowhorizon.hollowengine.common.addons.project

/** Something to tell the user about a project operation: a translation key and what to format it with. */
data class ProjectMessage(val key: String, val args: List<Any> = emptyList()) {
    constructor(key: String, vararg args: Any) : this(key, args.toList())
}

/** Thrown by project operations with a message meant for the user. */
class ProjectException(val messages: List<ProjectMessage>) : RuntimeException(messages.joinToString { it.key }) {
    constructor(message: ProjectMessage) : this(listOf(message))
}

internal object ProjectLang {
    private const val ROOT = "hollowengine.gui.ide.project"

    const val EXPORT_DEFAULT_ID = "$ROOT.export.default_id"
    const val EXPORT_NO_COMPILER = "$ROOT.export.no_compiler"
    const val EXPORT_COMPILE_FAILED = "$ROOT.export.compile_failed"
    const val EXPORT_ICON_MISSING = "$ROOT.export.icon_missing"
    const val EXPORT_COMPILING = "$ROOT.export.compiling"
    const val EXPORT_REMAPPING = "$ROOT.export.remapping"
    const val EXPORT_PACKING = "$ROOT.export.packing"

    const val IMPORT_NOT_ADDON = "$ROOT.import.not_addon"
    const val IMPORT_HAS_CLASSES = "$ROOT.import.has_classes"
    const val IMPORT_NO_SOURCES = "$ROOT.import.no_sources"
    const val IMPORT_UNSAFE_PATH = "$ROOT.import.unsafe_path"
}
