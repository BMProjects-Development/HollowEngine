package ru.hollowhorizon.hollowengine.client.models.fbx

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

/** Decode before scheduling GPU work so malformed embedded data can fall back to its external file. */
internal fun decodeEmbeddedTexture(bytes: ByteArray, element: Element, document: Document): ByteArray? {
    if (bytes.isEmpty()) return null
    return try {
        val image = ByteArrayInputStream(bytes).use { ImageIO.read(it) }
        if (image == null) {
            document.warn(element[1].parseAsString, "unrecognized embedded image; using external texture")
            null
        } else ByteArrayOutputStream().use { output ->
            if (!ImageIO.write(image, "png", output)) throw IOException("PNG encoder is unavailable")
            output.toByteArray()
        }
    } catch (ex: IOException) {
        document.warn(element[1].parseAsString, "invalid embedded image; using external texture: ${ex.message}")
        null
    }
}
