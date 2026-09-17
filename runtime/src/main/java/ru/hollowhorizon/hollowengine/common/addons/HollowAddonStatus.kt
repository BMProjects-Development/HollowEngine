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

enum class HollowAddonState {
    LOADED,
    DISABLED,
    RESTART_REQUIRED,
    WAITING_FOR_DEPENDENCIES,
    REJECTED,
    INACTIVE,
}

data class HollowAddonOperationResult(
    val successful: Boolean,
    val message: String,
)
