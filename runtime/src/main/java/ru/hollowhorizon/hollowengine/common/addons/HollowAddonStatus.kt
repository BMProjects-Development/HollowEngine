package ru.hollowhorizon.hollowengine.common.addons

import java.io.File

data class HollowAddonStatus(
    val descriptor: HollowAddonDescriptor,
    val state: HollowAddonState,
    val fileName: String,
    val details: String? = null,
)

/**
 * An installed addon and the jar its classes live in, which scripts of a namespace depending on it are
 * compiled against.
 */
data class HollowAddonInstallation(val descriptor: HollowAddonDescriptor, val classes: File)

/** A jar that carries an addon descriptor but could not be read, so it has no status of its own. */
data class HollowAddonProblem(val fileName: String, val message: String)

enum class HollowAddonState {
    LOADED,

    /** Switched off by an operator. */
    DISABLED,

    /** Starts with the next launch of the game. */
    RESTART_REQUIRED,

    /** An addon it depends on is not running. */
    WAITING_FOR_DEPENDENCIES,

    /** Its bundled libraries failed the bootstrap safety validation. */
    REJECTED,

    /** Made for the other physical side, which is not an error. */
    INACTIVE,

    /** Made for another mod loader. */
    INCOMPATIBLE,

    /** A mod it requires is not installed. */
    MISSING_MODS,

    /** Its own code failed while loading. */
    FAILED,

    /** Another copy of the same addon is used instead. */
    DUPLICATE,
}

data class HollowAddonOperationResult(
    val successful: Boolean,
    val message: String,
)
