package ru.hollowhorizon.hollowengine.client.models.fbx

import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelLoader
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelSide
import ru.hollowhorizon.hollowengine.client.utils.stream
import ru.hollowhorizon.hollowengine.common.models.ModelResourceIO
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FbxModelLoader: ModelLoader {
    override val supportedFormats = setOf("fbx")

    override suspend fun load(location: ResourceLocation, side: ModelSide): Model {
        return import(location, side).convert(location, side)
    }

    fun import(location: ResourceLocation, side: ModelSide = ModelSide.CLIENT): Document {
        return parse(location.readModelBytes(side))
    }

    fun parse(data: ByteArray): Document {
        val bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val tokens = ArrayList<Token>()
        val isBinary = data.size >= 18 && String(data, 0, 18, Charsets.US_ASCII) == "Kaydara FBX Binary"
        if (isBinary) {
            tokenizeBinary(tokens, bytes)
        } else tokenize(tokens, bytes)

        val parser = Parser(tokens, isBinary)

        return Document(parser)
    }

    private fun ResourceLocation.readModelBytes(side: ModelSide): ByteArray =
        when (side) {
            ModelSide.CLIENT -> stream.use { it.readBytes() }
            ModelSide.SERVER -> ModelResourceIO.open(this).use { it.readBytes() }
        }
}
