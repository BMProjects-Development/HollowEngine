package ru.hollowhorizon.hollowengine.common.vfx

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import ru.hollowhorizon.hollowengine.common.sounds.SoundFilterTypes

/**
 * Reads and writes json-like `.vfx` files.
 */
object VfxFormat {
    const val EXTENSION = ".vfx"

    /** Where a resource pack keeps its effects. */
    const val RESOURCE_FOLDER = "vfx"

    @Volatile
    private var cached: Json? = null

    @Volatile
    private var cachedRevision = -1

    val json: Json
        get() {
            val revision = VfxModuleRevision.current
            cached?.takeIf { cachedRevision == revision }?.let { return it }

            return build().also {
                cached = it
                cachedRevision = revision
            }
        }

    fun read(text: String): VfxEffect {
        if (text.isBlank()) return VfxEffect.EMPTY
        return json.decodeFromString(VfxEffect.serializer(), text)
    }

    fun write(effect: VfxEffect): String = json.encodeToString(VfxEffect.serializer(), effect)

    private fun build(): Json {
        val module = SerializersModule {
            VfxNodeTypes.registerInto(this)
            VfxModuleTypes.registerInto(this)
            SoundFilterTypes.registerInto(this)
        }
        return Json {
            serializersModule = module
            prettyPrint = true
            prettyPrintIndent = "  "
            ignoreUnknownKeys = true
            encodeDefaults = false
            allowComments = true
            allowTrailingComma = true
            allowSpecialFloatingPointValues = true
            classDiscriminator = "type"
        }
    }
}
