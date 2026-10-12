package ru.hollowhorizon.hollowengine.common.vfx

import kotlinx.serialization.Serializable

/**
 * The id of one property of a node that a timeline track can drive, such as `transform.position`,
 * `modules.<module>.<field>` or `uniforms.<name>`.
 */
@Serializable
@JvmInline
value class VfxProperty(val id: String) {
    override fun toString(): String = id

    companion object {
        val ENABLED = VfxProperty("enabled")
        val POSITION = VfxProperty("transform.position")
        val ROTATION = VfxProperty("transform.rotation")
        val SCALE = VfxProperty("transform.scale")

        val RATE = VfxProperty("emission.rate")
        val SHAPE_RADIUS = VfxProperty("shape.radius")
        val SHAPE_EXTENTS = VfxProperty("shape.extents")
        val SHAPE_ANGLE = VfxProperty("shape.angle")
        val SHAPE_THICKNESS = VfxProperty("shape.thickness")
        val LIFETIME = VfxProperty("spawn.lifetime")
        val SPEED = VfxProperty("spawn.speed")
        val OFFSET = VfxProperty("spawn.offset")
        val INHERIT_VELOCITY = VfxProperty("spawn.inherit_velocity")
        val SIZE = VfxProperty("appearance.size")
        val SPIN = VfxProperty("appearance.rotation")
        val COLOR = VfxProperty("appearance.color")
        val GRAVITY = VfxProperty("motion.gravity")
        val DRAG = VfxProperty("motion.drag")

        val TINT = VfxProperty("tint")
        val WIDTH = VfxProperty("width")
        val BEAM_END = VfxProperty("beam.end")
        val BEAM_NOISE = VfxProperty("beam.noise")
        val SHAKE_STRENGTH = VfxProperty("shake.strength")
        val SHAKE_FREQUENCY = VfxProperty("shake.frequency")
        val SOUND_VOLUME = VfxProperty("sound.volume")
        val SOUND_PITCH = VfxProperty("sound.pitch")

        fun module(moduleId: String, field: String) = VfxProperty("modules.$moduleId.$field")

        fun uniform(name: String) = VfxProperty("uniforms.$name")
    }
}

/** What a timeline track can drive. */
enum class VfxAnimatableKind(val channels: Int) {
    /** On or off, held between keys instead of interpolated. */
    TOGGLE(1), FLOAT(1), VEC3(3), ROTATION(3), COLOR(4),
}

/**
 * One property a timeline may animate, and its authored value, which a track starts from.
 */
class VfxAnimatable(
    val property: VfxProperty,
    val titleKey: String,
    val kind: VfxAnimatableKind,
    val ownerTitleKey: String? = null,
    val read: () -> FloatArray,
)

/**
 * The properties a timeline can animate.
 *
 * Placement is common to every node; the rest each node kind declares in [VfxNodeSpec.animatables].
 */
object VfxAnimatables {
    fun forNode(node: VfxNodeSpec): List<VfxAnimatable> = common(node) + node.animatables()

    fun of(node: VfxNodeSpec, property: VfxProperty): VfxAnimatable? =
        forNode(node).firstOrNull { it.property == property }

    fun scalar(property: VfxProperty, title: String, value: VfxValue, owner: String? = null) =
        VfxAnimatable(property, key(title), VfxAnimatableKind.FLOAT, owner) { floatArrayOf(value.constantOr(0f)) }

    fun vector(property: VfxProperty, title: String, value: VfxVec3Value, owner: String? = null) =
        VfxAnimatable(property, key(title), VfxAnimatableKind.VEC3, owner) { value.constants() }

    fun rotation(property: VfxProperty, title: String, value: VfxVec3Value) =
        VfxAnimatable(property, key(title), VfxAnimatableKind.ROTATION) { value.constants() }

    fun color(property: VfxProperty, title: String, value: VfxColorValue, owner: String? = null) =
        VfxAnimatable(property, key(title), VfxAnimatableKind.COLOR, owner) { value.constants() }

    fun key(name: String) = "hollowengine.gui.vfx.$name"

    private fun common(node: VfxNodeSpec): List<VfxAnimatable> = listOf(
        VfxAnimatable(VfxProperty.ENABLED, key("property_enabled"), VfxAnimatableKind.TOGGLE) {
            floatArrayOf(if (node.enabled) 1f else 0f)
        },
        VfxAnimatable(VfxProperty.POSITION, key("property_position"), VfxAnimatableKind.VEC3) {
            node.transform.position.let { floatArrayOf(it.x, it.y, it.z) }
        },
        VfxAnimatable(VfxProperty.ROTATION, key("property_rotation"), VfxAnimatableKind.ROTATION) {
            node.transform.rotation.let { floatArrayOf(it.x, it.y, it.z) }
        },
        VfxAnimatable(VfxProperty.SCALE, key("property_scale"), VfxAnimatableKind.VEC3) {
            node.transform.scale.let { floatArrayOf(it.x, it.y, it.z) }
        },
    )
}

/** The expression sources inside a value, for the one-pass compile of a file. */
fun VfxValue.sources(): List<String> = when (this) {
    is VfxValue.Expr -> listOf(source).filter { it.isNotBlank() }
    else -> emptyList()
}

fun VfxVec3Value.sources(): List<String> = x.sources() + y.sources() + z.sources()

fun VfxColorValue.sources(): List<String> = when (this) {
    is VfxColorValue.Channels -> r.sources() + g.sources() + b.sources() + a.sources()
    else -> emptyList()
}
