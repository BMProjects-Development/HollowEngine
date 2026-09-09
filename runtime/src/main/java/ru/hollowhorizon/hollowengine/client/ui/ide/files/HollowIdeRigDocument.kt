package ru.hollowhorizon.hollowengine.client.ui.ide.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeFileDocument
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hollowengine.common.utils.nbt.loadAsNBT
import ru.hollowhorizon.hollowengine.common.utils.nbt.save
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * An open `.rig` file: one [ModelRig] the editor rewrites whole.
 */
class HollowIdeRigDocument(bytes: ByteArray) : HollowIdeFileDocument {
    override val readOnly: Boolean = false

    var rig by mutableStateOf(decode(bytes))
        private set

    var isModified by mutableStateOf(false)
        private set

    var revision by mutableStateOf(0)
        private set

    private var editorState: Any? = null

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> editorState(create: () -> T): T = (editorState as? T) ?: create().also { editorState = it }

    fun edit(change: (ModelRig) -> ModelRig) {
        val next = change(rig)
        if (next == rig) return

        rig = next
        isModified = true
        revision++
    }

    override fun encode(): ByteArray {
        val tag = NBTFormat.serialize(ModelRig.serializer(), rig)
        return ByteArrayOutputStream().also { tag.save(it) }.toByteArray()
    }

    override fun reload(bytes: ByteArray) {
        rig = decode(bytes)
        isModified = false
        revision++
    }

    override fun markSaved() {
        isModified = false
    }

    private companion object {
        fun decode(bytes: ByteArray): ModelRig {
            if (bytes.isEmpty()) return ModelRig.EMPTY
            return try {
                val tag = ByteArrayInputStream(bytes).use { it.loadAsNBT() }
                NBTFormat.deserialize(ModelRig.serializer(), tag as? CompoundTag ?: return ModelRig.EMPTY)
            } catch (e: Exception) {
                HollowEngine.LOGGER.warn("Could not read rig: {}", e.message)
                ModelRig.EMPTY
            }
        }
    }
}
