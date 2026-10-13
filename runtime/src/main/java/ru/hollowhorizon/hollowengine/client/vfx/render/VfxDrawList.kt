package ru.hollowhorizon.hollowengine.client.vfx.render

import ru.hollowhorizon.hollowengine.client.decals.DecalDraw
import ru.hollowhorizon.hollowengine.client.decals.DecalGround
import org.joml.Matrix4f
import org.joml.Vector3f
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.vfx.VfxParticleLook
import ru.hollowhorizon.hollowengine.client.vfx.VfxParticles
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.vfx.VfxMaterialSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxMeshSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxModelSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxPlaneSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxPrimitive

/**
 * Where the particles are looked at from.
 *
 * Everything a draw places is expressed in one space, the one [modelView] starts from:
 * camera-relative world space in the world, effect space in the editor preview. The camera axes and
 * position are given in that same space, which is all a billboard or a ribbon needs to face the
 * viewer.
 */
class VfxView(
    val modelView: Matrix4f,
    val projection: Matrix4f,
    val right: Vector3f,
    val up: Vector3f,
    val eye: Vector3f,
    val time: Float,
    val decalGround: DecalGround? = null,
) {
    companion object
}

/**
 * The particles a renderer draws, and how they sit relative to it.
 *
 * Under an emitter, [offset], [spin] and [sizeScale] are the renderer node transform, applied to
 * each particle. A renderer on its own draws one particle of its own at the origin of [matrix],
 * which already carries its node frame.
 */
class VfxParticleBatch(
    val particles: VfxParticles,
    val look: FloatArray,
    /** Simulation space to the space of the view the batch is drawn with. */
    val matrix: Matrix4f,
    val uvColumns: Int = 1,
    val uvRows: Int = 1,
    val offset: Vec3f = Vec3f.ZERO,
    val spin: Vec3f = Vec3f.ZERO,
    val sizeScale: Vec3f = Vec3f.ONES,
    /** Multiplied into every particle color. */
    val tint: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
) {
    fun lookOf(slot: Int): Int = slot * VfxParticleLook.STRIDE
}

/** One plane renderer's worth of quads. */
class VfxQuadDraw(
    val plane: VfxPlaneSpec,
    val batch: VfxParticleBatch,
    val uniforms: VfxUniformValues?,
)

/** One mesh renderer's worth of instances of a built-in mesh. */
class VfxMeshDraw(
    val spec: VfxMeshSpec,
    val primitive: VfxPrimitive,
    val batch: VfxParticleBatch,
    val uniforms: VfxUniformValues?,
)

/** One model renderer's worth of model instances. */
class VfxModelDraw(
    val spec: VfxModelSpec,
    val model: ModelAttachment,
    val batch: VfxParticleBatch,
)

/**
 * Ribbons of one trail or beam node: strips of points, already in the space of the view.
 *
 * Each point is [STRIDE] floats: position, half width, color, the texture coordinate along the
 * ribbon, and the packed light.
 */
class VfxRibbonDraw(
    val material: VfxMaterialSpec,
    val uniforms: VfxUniformValues?,
    val points: FloatArray,
    /** Start point and point count of every strip, in pairs. */
    val strips: IntArray,
    val stripCount: Int,
    /** Whether the texture coordinate along the ribbon runs past 1, so the texture has to repeat. */
    val repeat: Boolean,
) {
    companion object {
        const val STRIDE = 10
    }
}

/** A full-screen pass, with the uniforms it is drawn with this frame and where its node is in the space of the view. */
class VfxPostDraw(val shader: String, val uniforms: VfxUniformValues, val position: Vector3f)

/** A sky node's pass, with where its node is in the space of the view and how long the effect has played. */
class VfxSkyDraw(val shader: String, val uniforms: VfxUniformValues, val position: Vector3f, val time: Float)

/**
 * Everything the playing effects draw this frame, sorted by how it is drawn.
 */
class VfxDrawList {
    val quads = ArrayList<VfxQuadDraw>()
    val meshes = ArrayList<VfxMeshDraw>()
    val models = ArrayList<VfxModelDraw>()
    val ribbons = ArrayList<VfxRibbonDraw>()
    val posts = ArrayList<VfxPostDraw>()
    val skies = ArrayList<VfxSkyDraw>()

    /** Decals of decal nodes and of models drawn this frame; drawn first, onto the scene alone. */
    val decals = ArrayList<DecalDraw>()

    /** Camera shake in degrees: pitch, yaw and roll, summed over every shaking node. */
    val shake = FloatArray(3)

    val isEmpty: Boolean
        get() = quads.isEmpty() && meshes.isEmpty() && models.isEmpty() && ribbons.isEmpty() && posts.isEmpty() &&
                skies.isEmpty() && decals.isEmpty()

    /**
     * Whether anything reads the scene textures: a surface with a shader of the author, which may,
     * or one with softness, which reads the depth.
     */
    val readsScene: Boolean
        get() = materials().any { it.shader != null || it.softness > 0f }

    /** Whether any surface glows, which is what the glow pass is drawn for. */
    val glows: Boolean
        get() = materials().any { it.effectiveGlow() > 0f }

    private fun materials(): Sequence<VfxMaterialSpec> =
        quads.asSequence().map { it.plane.material } + meshes.asSequence().map { it.spec.material } +
                ribbons.asSequence().map { it.material }

    fun clear() {
        quads.clear()
        meshes.clear()
        models.clear()
        ribbons.clear()
        posts.clear()
        skies.clear()
        decals.clear()
        shake.fill(0f)
    }
}
