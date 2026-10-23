package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.HollowEngine

class FbxImportException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

enum class InvalidAnimationPolicy { SKIP, REJECT }

data class FbxImportOptions(val invalidAnimationPolicy: InvalidAnimationPolicy = InvalidAnimationPolicy.SKIP)

data class FbxImportWarning(val objectName: String, val message: String)

internal fun Document.warn(objectName: String, message: String) {
    warnings += FbxImportWarning(objectName, message)
    HollowEngine.LOGGER.warn("FBX [$objectName]: $message")
}
