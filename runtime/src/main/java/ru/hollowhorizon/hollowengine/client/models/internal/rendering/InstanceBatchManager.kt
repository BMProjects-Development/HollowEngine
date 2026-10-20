package ru.hollowhorizon.hollowengine.client.models.internal.rendering

import com.mojang.blaze3d.systems.RenderSystem
import org.joml.Matrix3f
import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.client.models.internal.Material
import ru.hollowhorizon.hollowengine.client.models.internal.packedLight
import ru.hollowhorizon.hollowengine.client.models.internal.v2.MeshAttachment
import ru.hollowhorizon.hollowengine.client.utils.InstancingEntityInfo
import ru.hollowhorizon.hollowengine.client.utils.instancingBackend
import ru.hollowhorizon.hollowengine.client.utils.instancingEntityInfo
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f

/** What one instanced call draws: a part of a model's geometry in one material. */
data class StaticBatchKey(val part: StaticPart, val material: Material)

/**
 * One instance of a [StaticPart], captured while its model was drawn.
 *
 * [slotMatrices] hold the node matrix of each slot of the part, 16 floats each in column order, and [drawn] says
 * which slots this call draws: a hidden node, or one dressed in another material, is left to zero.
 */
class StaticInstance(
    val part: StaticPart,
    val material: Material,
    val modelView: Matrix4f,
    val modelNormal: Matrix3f,
    val viewNormal: Matrix3f,
    val slotMatrices: FloatArray,
    val drawn: BooleanArray,
    val overlay: Int,
    val light: Int,
    val entityInfo: InstancingEntityInfo,
) {
    /** Squared distance of the model's origin from the camera, to draw translucent instances far to near. */
    val sortKey: Float = modelView.m30() * modelView.m30() + modelView.m31() * modelView.m31() + modelView.m32() * modelView.m32()

    /** The normal matrix the slots' own normal matrices go under, for a shader laid out as [layout] says. */
    fun baseNormal(layout: InstancedShaderLayoutMode, into: Matrix3f): Matrix3f =
        composeBaseNormal(modelNormal, viewNormal, layout, into)

    /** The model-view and normal matrices of the slot at [local], for a shader laid out as [layout] says. */
    fun slotTransform(local: Int, layout: InstancedShaderLayoutMode, into: SlotTransform = SlotTransform()): SlotTransform =
        composeSlotTransform(modelView, modelNormal, viewNormal, slotMatrices, local, layout, into)

    companion object {
        const val MATRIX_FLOATS = 16
    }
}

/**
 * Puts the slot at [local] of [slotMatrices] under [modelView]. The normal matrix stays in the space of the model
 * for [InstancedShaderLayoutMode.FIXED] and is carried into the view for [InstancedShaderLayoutMode.RUNTIME].
 */
internal fun composeSlotTransform(
    modelView: Matrix4f,
    modelNormal: Matrix3f,
    viewNormal: Matrix3f,
    slotMatrices: FloatArray,
    local: Int,
    layout: InstancedShaderLayoutMode,
    into: SlotTransform,
): SlotTransform {
    into.slot.set(slotMatrices, local * StaticInstance.MATRIX_FLOATS)
    into.modelView.set(modelView).mul(into.slot)
    normalMatrixOf(into.slot, into.slotNormal)
    composeBaseNormal(modelNormal, viewNormal, layout, into.normal).mul(into.slotNormal)
    return into
}

private fun composeBaseNormal(modelNormal: Matrix3f, viewNormal: Matrix3f, layout: InstancedShaderLayoutMode, into: Matrix3f): Matrix3f =
    when (layout) {
        InstancedShaderLayoutMode.FIXED -> into.set(modelNormal)
        InstancedShaderLayoutMode.RUNTIME -> into.set(viewNormal).mul(modelNormal)
    }

/** Scratch matrices for [StaticInstance.slotTransform]. */
class SlotTransform {
    val modelView = Matrix4f()
    val normal = Matrix3f()
    internal val slot = Matrix4f()
    internal val slotNormal = Matrix3f()
}

/** How the shader takes the normal matrix: in the space of the model, or of the view as shader packs do. */
enum class InstancedShaderLayoutMode {
    FIXED,
    RUNTIME,
}

object InstanceBatchManager {
    private val batches = LinkedHashMap<StaticBatchKey, MutableList<StaticInstance>>()

    fun canBatch(): Boolean = instancingBackend.canBatch()

    fun submit(instance: StaticInstance) {
        batches.getOrPut(StaticBatchKey(instance.part, instance.material)) { ArrayList() }.add(instance)
    }

    fun flush() {
        if (batches.isEmpty()) return
        try {
            instancingBackend.flush(batches)
        } finally {
            clear()
        }
    }

    fun clear() {
        batches.clear()
    }
}

class StaticSubmissions(geometry: StaticGeometry) {
    internal val byPart: Array<ArrayList<KeptSlots>> = Array(geometry.parts.size) { ArrayList(1) }

    internal val drawn = ArrayList<Pair<StaticPart, KeptSlots>>()
    internal var poseVersion = Int.MIN_VALUE
    internal var appearanceVersion = Int.MIN_VALUE
}

internal class KeptSlots(var material: Material, slotCount: Int) {
    val drawn = BooleanArray(slotCount)
    val matrices = FloatArray(slotCount * StaticInstance.MATRIX_FLOATS)
    var poseVersion = Int.MIN_VALUE
}

fun RenderContext.submitStatic(
    geometry: StaticGeometry,
    meshes: Array<MeshAttachment?>,
    kept: StaticSubmissions,
    poseVersion: Int,
    appearanceVersion: Int,
) {
    val renderModelView = RenderSystem.getModelViewMatrix()
    val modelView = Matrix4f(renderModelView).mul(stack.last().pose())
    val modelNormal = Matrix3f(stack.last().normal())
    val viewNormal = normalMatrixOf(renderModelView)
    val entityInfo = instancingEntityInfo
    val batching = allowInstancing && InstanceBatchManager.canBatch()
    val immediate = if (batching) null else LinkedHashMap<StaticBatchKey, MutableList<StaticInstance>>()
    fun hand(part: StaticPart, slots: KeptSlots) {
        val material = slots.material
        val instance = StaticInstance(
            part, material, modelView, modelNormal, viewNormal, slots.matrices, slots.drawn,
            overlay, material.packedLight(light), entityInfo,
        )
        if (immediate == null) {
            InstanceBatchManager.submit(instance)
        } else {
            immediate.getOrPut(StaticBatchKey(part, material)) { ArrayList() }.add(instance)
        }
    }

    if (kept.poseVersion == poseVersion && kept.appearanceVersion == appearanceVersion) {
        kept.drawn.forEach { (part, slots) -> hand(part, slots) }
        if (!immediate.isNullOrEmpty()) instancingBackend.flush(immediate)
        return
    }
    kept.drawn.clear()
    kept.poseVersion = poseVersion
    kept.appearanceVersion = appearanceVersion

    val materials = ArrayList<Material>(1)
    geometry.parts.forEachIndexed { index, part ->
        materials.clear()
        part.slots.forEach { slot ->
            val mesh = meshes[slot.index]?.takeIf { it.isVisible } ?: return@forEach
            if (mesh.material !in materials) materials += mesh.material
        }
        val partKept = kept.byPart[index]
        partKept.retainAll { it.material in materials }

        for (material in materials) {
            val slots = partKept.firstOrNull { it.material == material }
                ?: KeptSlots(material, part.slots.size).also(partKept::add)
            slots.material = material
            fillSlots(part, meshes, material, slots, poseVersion)
            kept.drawn += part to slots
            hand(part, slots)
        }
    }

    if (!immediate.isNullOrEmpty()) instancingBackend.flush(immediate)
}

/** Brings [slots] up to date: which slots of [part] draw in [material], and their matrices after a new pose. */
private fun fillSlots(part: StaticPart, meshes: Array<MeshAttachment?>, material: Material, slots: KeptSlots, poseVersion: Int) {
    var changed = slots.poseVersion != poseVersion
    part.slots.forEach { slot ->
        val shown = meshes[slot.index]?.let { it.isVisible && it.material == material } == true
        if (slots.drawn[slot.local] != shown) {
            slots.drawn[slot.local] = shown
            changed = true
        }
    }
    if (!changed) return

    slots.poseVersion = poseVersion
    part.slots.forEach { slot ->
        val at = slot.local * StaticInstance.MATRIX_FLOATS
        val mesh = meshes[slot.index]
        if (slots.drawn[slot.local] && mesh != null) {
            mesh.matrix.putColumns(slots.matrices, at)
        } else {
            slots.matrices.fill(0f, at, at + StaticInstance.MATRIX_FLOATS)
        }
    }
}

private fun Mat4f.putColumns(into: FloatArray, at: Int) {
    into[at] = m00; into[at + 1] = m10; into[at + 2] = m20; into[at + 3] = m30
    into[at + 4] = m01; into[at + 5] = m11; into[at + 6] = m21; into[at + 7] = m31
    into[at + 8] = m02; into[at + 9] = m12; into[at + 10] = m22; into[at + 11] = m32
    into[at + 12] = m03; into[at + 13] = m13; into[at + 14] = m23; into[at + 15] = m33
}
