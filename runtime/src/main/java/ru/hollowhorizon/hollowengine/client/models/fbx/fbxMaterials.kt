package ru.hollowhorizon.hollowengine.client.models.fbx

import ru.hollowhorizon.hollowengine.client.models.util.bool
import ru.hollowhorizon.hollowengine.common.utils.math.Vec2f
import java.util.Base64

class Texture(id: Long, element: Element, doc: Document, name: String) : Object(id, element, name) {
    val uvTrans = element.scope["ModelUVTranslation"]?.let { Vec2f(it[0].parseAsFloat, it[1].parseAsFloat) }
        ?: Vec2f(0f)
    val uvScaling = element.scope["ModelUVScaling"]?.let { Vec2f(it[0].parseAsFloat, it[1].parseAsFloat) }
        ?: Vec2f(1f)

    val type = element.scope["Type"]?.get(0)?.parseAsString ?: ""
    val relativeFileName = (element.scope["RelativeFileName"] ?: element.scope["RelativeFilename"])?.get(0)?.parseAsString ?: ""
    val fileName = (element.scope["FileName"] ?: element.scope["Filename"])?.get(0)?.parseAsString ?: ""
    val props = getPropertyTable(doc, "Texture.FbxFileTexture", element, element.compound)

    var media: Video? = null

    init {
        val conns = doc.getConnectionsByDestinationSequenced(id)
        for (con in conns) {
            val ob = con.sourceObject
            if (ob == null) {
                domWarning("failed to read source object for texture link, ignoring", element)
                continue
            }
            media = ob as? Video
        }
    }
}

class LayeredTexture(id: Long, element: Element, name: String) : Object(id, element, name) {

    val textures = ArrayList<Texture>()
    val alphas = ArrayList<Float>().also { element.compound?.get("Alphas")?.parseFloatsDataArray(it) }

    fun fillTexture(doc: Document) {
        val conns = doc.getConnectionsByDestinationSequenced(id)
        for (i in conns.indices) {
            val con = conns[i]

            val ob = con.sourceObject
            if (ob == null) {
                domWarning("failed to read source object for texture link, ignoring", element)
                continue
            }
            (ob as? Texture)?.let { textures += it }
        }
    }

}

class Video(id: Long, element: Element, doc: Document, name: String) : Object(id, element, name) {
    val type = element.scope["Type"]?.get(0)?.parseAsString ?: ""
    val relativeFileName = element.scope["RelativeFilename"]?.get(0)?.parseAsString ?: ""
    val fileName = (element.scope["FileName"] ?: element.scope["Filename"])?.get(0)?.parseAsString ?: ""

    val content: ByteArray = element.compound?.get("Content")?.let { contentElement ->
        val token = contentElement.tokens.firstOrNull() ?: return@let byteArrayOf()
        try {
            if (!token.isBinary) {
                Base64.getDecoder().decode(contentElement.tokens.joinToString("") { it.parseAsString }.filterNot { it.isWhitespace() })
            } else {
                if (token.end - token.begin < 5 || token.buffer[token.begin] != 'R'.code.toByte()) {
                    domError("expected raw embedded texture data", contentElement)
                }
                val length = token.buffer.getInt(token.begin + 1)
                if (length < 0 || length != token.end - token.begin - 5) domError("invalid embedded texture length", contentElement)
                ByteArray(length) { token.buffer[token.begin + 5 + it] }
            }
        } catch (ex: IllegalArgumentException) {
            domWarning("invalid embedded texture encoding: ${ex.message}", contentElement)
            byteArrayOf()
        }
    } ?: byteArrayOf()

    internal val imageContent by lazy { decodeEmbeddedTexture(content, element, doc) }

    val props = getPropertyTable(doc, "Video.FbxVideo", element, element.compound)
}

class Material(id: Long, element: Element, doc: Document, name: String) : Object(id, element, name) {
    val shading: String
    val multiLayer: Boolean
    val props: PropertyTable

    init {
        val sc = element.scope

        val shadingModel = sc["ShadingModel"]
        multiLayer = (sc["MultiLayer"]?.get(0)?.parseAsInt ?: 0).bool

        shading = if (shadingModel != null) shadingModel[0].parseAsString
        else "phong".also { domWarning("shading mode not specified, assuming phong", element) }

        val templateName = when (shading.lowercase()) {
            "phong" -> "Material.FbxSurfacePhong"
            "lambert" -> "Material.FbxSurfaceLambert"
            else -> "".also { domWarning("shading mode not recognized: $shading", element) }
        }
        props = getPropertyTable(doc, templateName, element, sc)
    }

    val textures = mutableMapOf<String, Texture>()
    val layeredTextures = mutableMapOf<String, LayeredTexture>()

    init {
        val conns = doc.getConnectionsByDestinationSequenced(id)
        for (con in conns) {
            if (con.prop.isEmpty()) continue

            val ob = con.sourceObject
            if (ob == null) {
                domWarning("failed to read source object for texture link, ignoring", element)
                continue
            }

            val tex = ob as? Texture
            if (tex == null) {
                val layeredTexture = ob as? LayeredTexture
                if (layeredTexture == null) {
                    domWarning("source object for texture link is not a texture or layered texture, ignoring", element)
                    continue
                }
                val prop = con.prop
                if (layeredTextures.contains(prop)) domWarning("duplicate layered texture link: $prop", element)

                layeredTextures[prop] = layeredTexture
                layeredTexture.fillTexture(doc)
            } else {
                val prop = con.prop
                if (textures.contains(prop)) domWarning("duplicate texture link: $prop", element)

                textures[prop] = tex
            }
        }
    }
}
