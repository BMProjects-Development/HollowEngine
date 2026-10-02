package ru.hollowhorizon.hollowengine.common.colliders

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.HitResult
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.api.Syncable
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.api.set
import ru.hollowhorizon.hollowengine.common.attachments.components.ComponentDescriptorRegistry
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorRange
import ru.hollowhorizon.hollowengine.common.models.ModelRig

private const val LANG = "hollowengine.component.hollowengine.entity.colliders"
private const val RIG_LANG = "hollowengine.gui.rig_editor.collider"

/**
 * What a script changed about the colliders of one entity, over what its rig says: kept with the entity
 * and sent to the clients, which aim at the colliders and bump into them themselves.
 */
@Registerable
@Syncable
@Serializable
@SerialName("hollowengine:entity/colliders")
data class CollidersComponent(
    @EditorDescription("$LANG.overrides.hint")
    val overrides: Map<String, ColliderOverride> = emptyMap(),
)

/** One collider's changes; what is left null stays as the rig has it. */
@Serializable
data class ColliderOverride(
    @EditorName("$LANG.enabled")
    @EditorDescription("$LANG.enabled.hint")
    val enabled: Boolean? = null,
    @EditorName("$RIG_LANG.modes.hit")
    val hit: Boolean? = null,
    @EditorName("$RIG_LANG.modes.interact")
    val interact: Boolean? = null,
    @EditorName("$RIG_LANG.modes.contact")
    val contact: ColliderContact? = null,
    @EditorName("$RIG_LANG.modes.force")
    @EditorRange(min = 0.0)
    val force: Float? = null,
) {
    val isEmpty: Boolean get() = this == ColliderOverride()

    fun applyTo(modes: ColliderModes): ColliderModes = ColliderModes(
        hit = hit ?: modes.hit,
        interact = interact ?: modes.interact,
        contact = contact ?: modes.contact,
        force = force ?: modes.force,
    )
}

val Entity.collidersComponent: CollidersComponent?
    get() = AttachmentRegistry.attachmentsOrNull(this)?.components?.readOnly?.values?.firstNotNullOfOrNull { it as? CollidersComponent }

/** [placed], the colliders of [entity] as its rig places them, with what scripts changed about them. */
internal fun applyOverrides(entity: Entity, placed: List<EntityCollider>): List<EntityCollider> {
    val overrides = entity.collidersComponent?.overrides?.takeIf { it.isNotEmpty() } ?: return placed
    return placed.mapNotNull { collider ->
        val override = overrides[collider.name] ?: return@mapNotNull collider
        if (override.enabled == false) return@mapNotNull null
        EntityCollider(collider.name, collider.bone, collider.spec.copy(modes = override.applyTo(collider.spec.modes)), collider.box)
    }
}

/** The colliders [rig] gives [entity], as they are with what scripts changed about them. */
internal fun effectiveSpecs(entity: Entity, rig: ModelRig): List<ColliderAttachmentSpec> {
    val overrides = entity.collidersComponent?.overrides.orEmpty()
    return rig.colliders.mapNotNull { (_, spec) ->
        val override = overrides[spec.id] ?: return@mapNotNull spec
        if (override.enabled == false) null else spec.copy(modes = override.applyTo(spec.modes))
    }
}

/** The collider [name] of this entity, to read and change from a script. */
fun Entity.collider(name: String): ColliderHandle = ColliderHandle(this, name)

/**
 * One collider of one entity, as scripts see it: what the rig says with what scripts changed on top.
 * Reading works on either side; changes are made on the server, which saves them with the entity and
 * sends them to the clients.
 */
class ColliderHandle internal constructor(val entity: Entity, val name: String) {
    /** The collider as the rig has it, or null when the entity's rig has no collider of that name. */
    val spec: ColliderAttachmentSpec? get() = EntityColliders.rig(entity)?.colliders?.firstOrNull { it.second.id == name }?.second

    val exists: Boolean get() = spec != null

    /** Where the collider is now, or null when it is turned off or the model has not placed it yet. */
    val placed: EntityCollider? get() = entity.colliders.firstOrNull { it.name == name }

    var enabled: Boolean
        get() = override.enabled ?: true
        set(value) = change { it.copy(enabled = value) }

    var hit: Boolean
        get() = modes.hit
        set(value) = change { it.copy(hit = value) }

    var interact: Boolean
        get() = modes.interact
        set(value) = change { it.copy(interact = value) }

    var contact: ColliderContact
        get() = modes.contact
        set(value) = change { it.copy(contact = value) }

    var force: Float
        get() = modes.force
        set(value) = change { it.copy(force = value) }

    /** Puts the collider back the way the rig has it. */
    fun reset() = change { ColliderOverride() }

    private val override: ColliderOverride get() = entity.collidersComponent?.overrides?.get(name) ?: ColliderOverride()

    private val modes: ColliderModes get() = override.applyTo(spec?.modes ?: ColliderModes())

    private fun change(edit: (ColliderOverride) -> ColliderOverride) {
        check(!entity.level().isClientSide) { "Colliders are changed on the server; the clients follow" }
        require(exists) { "${entity.name.string} has no collider named \"$name\"" }

        val overrides = entity.collidersComponent?.overrides.orEmpty().toMutableMap()
        val changed = edit(override)
        if (changed.isEmpty) overrides.remove(name) else overrides[name] = changed

        if (overrides.isNotEmpty()) {
            entity set CollidersComponent(overrides)
        } else {
            ComponentDescriptorRegistry.idFor(CollidersComponent::class)
                ?.let { AttachmentRegistry.attachmentsOrNull(entity)?.components?.asMutableMap()?.remove(it) }
        }
    }
}

/**
 * Deals [amount] of [source] to this entity as a hit on its collider [name]: `onColliderHit` and
 * `DamageSource.colliderHit` see it like any other hit there, a scripted attack on a weak spot, say.
 */
fun Entity.hurtCollider(name: String, source: DamageSource, amount: Float): Boolean {
    val (bone, _) = EntityColliders.rig(this)?.colliders?.firstOrNull { it.second.id == name }
        ?: throw IllegalArgumentException("${this.name.string} has no collider named \"$name\"")
    val location = colliders.firstOrNull { it.name == name }?.box?.center ?: boundingBox.center
    return hurt(ColliderDamageSource(source, ColliderHit(this, name, bone, location)), amount)
}

/** The collider this hit landed on, the crosshair's included, or null when it landed on none. */
val HitResult?.collider: ColliderHit? get() = (this as? ColliderHitResult)?.hit
