package ru.hollowhorizon.hollowengine.client.discord

import kotlinx.serialization.json.*
import java.net.URI

/** Immutable snapshot; constructing a new activity never performs IPC. */
data class DiscordActivity(
    val details: String? = null,
    val state: String? = null,
    val startTimestamp: Long? = null,
    val endTimestamp: Long? = null,
    val assets: DiscordAssets? = null,
    val buttons: List<DiscordButton> = emptyList(),
) {
    init {
        validateText(details, "details")
        validateText(state, "state")
        require(startTimestamp == null || startTimestamp >= 0) { "Start timestamp must be Unix seconds" }
        require(endTimestamp == null || endTimestamp >= 0) { "End timestamp must be Unix seconds" }
        require(startTimestamp == null || endTimestamp == null || endTimestamp >= startTimestamp)
        require(buttons.size <= 2) { "Discord supports at most two activity buttons" }
    }

    internal fun toJson(): JsonObject = buildJsonObject {
        put("type", 0)
        details?.let { put("details", it) }
        state?.let { put("state", it) }
        if (startTimestamp != null || endTimestamp != null) put("timestamps", buildJsonObject {
            startTimestamp?.let { put("start", it) }
            endTimestamp?.let { put("end", it) }
        })
        assets?.let { put("assets", it.toJson()) }
        if (buttons.isNotEmpty()) put("buttons", buildJsonArray {
            buttons.forEach { button ->
                add(buildJsonObject {
                    put("label", button.label)
                    put("url", button.url)
                })
            }
        })
    }
}

data class DiscordAssets(
    val largeImage: String? = null,
    val largeText: String? = null,
    val smallImage: String? = null,
    val smallText: String? = null,
) {
    init {
        validateText(largeImage, "largeImage", 1, 256)
        validateText(smallImage, "smallImage", 1, 256)
        validateText(largeText, "largeText")
        validateText(smallText, "smallText")
        require(largeText == null || largeImage != null)
        require(smallText == null || smallImage != null)
    }

    internal fun toJson(): JsonObject = buildJsonObject {
        largeImage?.let { put("large_image", it) }
        largeText?.let { put("large_text", it) }
        smallImage?.let { put("small_image", it) }
        smallText?.let { put("small_text", it) }
    }
}

data class DiscordButton(val label: String, val url: String) {
    init {
        validateText(label, "label", 1, 32)
        require(url.length in 1..512)
        val uri = URI(url)
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrEmpty()) {
            "Activity buttons require an absolute HTTP(S) URL"
        }
    }
}

/** A null activity clears presence while retaining the application's connection. */
data class DiscordPresence(val applicationId: String, val activity: DiscordActivity?) {
    init {
        require(applicationId.toULongOrNull()?.let { it > 0uL } == true && applicationId.all { it in '0'..'9' }) {
            "Discord application ID must be a positive snowflake"
        }
    }

    internal fun command(pid: Long, nonce: String): JsonObject = buildJsonObject {
        put("cmd", "SET_ACTIVITY")
        put("nonce", nonce)
        put("args", buildJsonObject {
            put("pid", pid)
            put("activity", activity?.toJson() ?: JsonNull)
        })
    }
}

private fun validateText(value: String?, name: String, min: Int = 2, max: Int = 128) {
    require(value == null || value.codePointCount(0, value.length) in min..max) {
        "$name must contain $min..$max characters when supplied"
    }
}
