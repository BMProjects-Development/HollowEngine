package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.Composable
import ru.hollowhorizon.hollowengine.client.ui.Text
import ru.hollowhorizon.hollowengine.client.ui.inspector.Pills
import ru.hollowhorizon.hollowengine.client.ui.inspector.ToggleRow
import ru.hollowhorizon.hollowengine.common.vfx.VfxDirectionMode
import ru.hollowhorizon.hollowengine.common.vfx.VfxEmitterSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxParticleEvent
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import ru.hollowhorizon.hollowengine.common.vfx.VfxShape
import ru.hollowhorizon.hollowengine.common.vfx.VfxShapeKind
import ru.hollowhorizon.hollowengine.common.vfx.VfxSimulationSpace
import ru.hollowhorizon.hollowengine.common.vfx.VfxSubEmission

/**
 * How many particles, where they are born, what they start with and how they move. How they look is
 * up to the renderers under the emitter, each for itself.
 *
 * An emitter [underEmitter] may spawn from the particles of that one instead; its own emission then
 * does nothing but cap the count.
 */
@Composable
internal fun EmitterFields(
    document: VfxEditing,
    state: VfxInspectorState,
    emitter: VfxEmitterSpec,
    underEmitter: Boolean,
) {
    val sub = emitter.subEmission?.takeIf { underEmitter }
    if (underEmitter) SubEmissionFields(document, state, emitter)

    val emission = emitter.emission
    Folding(state, "emission", vfxText("section_emission"), VfxIcons.EMISSION) {
        if (sub != null) {
            VfxIntRow(vfxText("max_particles"), emission.maxParticles, vfxText("max_particles_hint"), min = 1, max = 20_000) {
                document.replace(emitter.copy(emission = emission.copy(maxParticles = it)))
            }
            return@Folding
        }
        VfxValueRow(vfxText("rate"), emission.rate, VfxProperty.RATE, vfxText("rate_hint")) {
            document.replace(emitter.copy(emission = emission.copy(rate = it)))
        }
        VfxIntRow(vfxText("burst"), emission.burst, vfxText("burst_hint"), min = 0, max = 4096) {
            document.replace(emitter.copy(emission = emission.copy(burst = it)))
        }
        VfxNumberRow(vfxText("duration"), emission.duration, vfxText("duration_hint"), min = 0f) {
            document.replace(emitter.copy(emission = emission.copy(duration = it)))
        }
        ToggleRow(vfxText("loop"), emission.loop) {
            document.replace(emitter.copy(emission = emission.copy(loop = it)))
        }
        if (emission.loop) {
            VfxNumberRow(vfxText("loop_delay"), emission.loopDelay, vfxText("loop_delay_hint"), min = 0f) {
                document.replace(emitter.copy(emission = emission.copy(loopDelay = it)))
            }
        }
        VfxIntRow(vfxText("max_particles"), emission.maxParticles, vfxText("max_particles_hint"), min = 1, max = 20_000) {
            document.replace(emitter.copy(emission = emission.copy(maxParticles = it)))
        }
    }

    ShapeFields(document, state, emitter)

    val spawn = emitter.spawn
    Folding(state, "spawn", vfxText("section_spawn"), VfxIcons.SPAWN) {
        VfxValueRow(vfxText("lifetime"), spawn.lifetime, VfxProperty.LIFETIME, vfxText("lifetime_hint")) {
            document.replace(emitter.copy(spawn = spawn.copy(lifetime = it)))
        }
        VfxValueRow(vfxText("speed"), spawn.speed, VfxProperty.SPEED, vfxText("speed_hint")) {
            document.replace(emitter.copy(spawn = spawn.copy(speed = it)))
        }
        VfxVec3Row(vfxText("spawn_offset"), spawn.offset, VfxProperty.OFFSET, vfxText("spawn_offset_hint")) {
            document.replace(emitter.copy(spawn = spawn.copy(offset = it)))
        }
        VfxValueRow(
            vfxText("inherit_velocity"),
            spawn.inheritVelocity,
            VfxProperty.INHERIT_VELOCITY,
            vfxText("inherit_velocity_hint"),
        ) {
            document.replace(emitter.copy(spawn = spawn.copy(inheritVelocity = it)))
        }
    }

    val motion = emitter.motion
    Folding(state, "motion", vfxText("section_motion"), VfxIcons.MOTION) {
        VfxValueRow(vfxText("gravity"), motion.gravity, VfxProperty.GRAVITY, vfxText("gravity_hint")) {
            document.replace(emitter.copy(motion = motion.copy(gravity = it)))
        }
        VfxValueRow(vfxText("drag"), motion.drag, VfxProperty.DRAG, vfxText("drag_hint")) {
            document.replace(emitter.copy(motion = motion.copy(drag = it)))
        }
    }

    VfxModuleSections(document, state, emitter)

    Folding(state, "space", vfxText("section_space"), VfxIcons.SPACE) {
        Pills(VfxSimulationSpace.entries, emitter.space, { vfxText("space_${it.name.lowercase()}") }) {
            document.replace(emitter.copy(space = it))
        }
        if (emitter.space == VfxSimulationSpace.LOCAL) {
            ToggleRow(vfxText("inherit_rotation"), emitter.inheritRotation) {
                document.replace(emitter.copy(inheritRotation = it))
            }
            ToggleRow(vfxText("inherit_scale"), emitter.inheritScale) {
                document.replace(emitter.copy(inheritScale = it))
            }
        }
        VfxNumberRow(vfxText("max_step"), emitter.maxStep, vfxText("max_step_hint"), min = 1f / 240f, max = 0.25f) {
            document.replace(emitter.copy(maxStep = it))
        }
    }
}

/** Whether and when the emitter spawns from the particles of the emitter it sits under. */
@Composable
private fun SubEmissionFields(document: VfxEditing, state: VfxInspectorState, emitter: VfxEmitterSpec) {
    val sub = emitter.subEmission
    fun update(next: VfxSubEmission) = document.replace(emitter.copy(subEmission = next))

    Folding(state, "sub_emission", vfxText("section_sub_emission"), VfxIcons.SUB_EMITTER) {
        ToggleRow(vfxText("sub_enabled"), sub != null, hint = vfxText("sub_enabled_hint")) { on ->
            document.replace(emitter.copy(subEmission = if (on) VfxSubEmission() else null))
        }
        if (sub == null) return@Folding

        Pills(VfxParticleEvent.entries, sub.event, { vfxText("sub_event_${it.name.lowercase()}") }) {
            update(sub.copy(event = it))
        }
        val countHint = if (sub.event == VfxParticleEvent.ALIVE) "sub_count_alive_hint" else "sub_count_hint"
        VfxValueRow(vfxText("sub_count"), sub.count, hint = vfxText(countHint)) { update(sub.copy(count = it)) }
        VfxValueRow(vfxText("inherit_velocity"), sub.inheritVelocity, hint = vfxText("sub_inherit_velocity_hint")) {
            update(sub.copy(inheritVelocity = it))
        }
    }
}

/** The shape shows only what chosen kind reads. */
@Composable
private fun ShapeFields(document: VfxEditing, state: VfxInspectorState, emitter: VfxEmitterSpec) {
    val shape = emitter.shape
    fun update(next: VfxShape) = document.replace(emitter.copy(shape = next))

    Folding(state, "shape", vfxText("section_shape"), VfxIcons.SHAPE) {
        Pills(VfxShapeKind.entries, shape.kind, { vfxText("shape_${it.name.lowercase()}") }) {
            update(shape.copy(kind = it))
        }

        val round = shape.kind == VfxShapeKind.SPHERE || shape.kind == VfxShapeKind.CONE || shape.kind == VfxShapeKind.DISC
        if (round) {
            VfxValueRow(vfxText("radius"), shape.radius, VfxProperty.SHAPE_RADIUS) { update(shape.copy(radius = it)) }
            VfxValueRow(vfxText("thickness"), shape.thickness, VfxProperty.SHAPE_THICKNESS, vfxText("thickness_hint")) {
                update(shape.copy(thickness = it))
            }
        }
        if (shape.kind == VfxShapeKind.CONE) {
            VfxValueRow(vfxText("angle"), shape.angle, VfxProperty.SHAPE_ANGLE, vfxText("angle_hint")) {
                update(shape.copy(angle = it))
            }
        }
        if (shape.kind == VfxShapeKind.BOX) {
            VfxVec3Row(vfxText("extents"), shape.extents, VfxProperty.SHAPE_EXTENTS, vfxText("extents_hint")) {
                update(shape.copy(extents = it))
            }
        }
        if (shape.kind == VfxShapeKind.LINE) {
            VfxVec3Row(vfxText("line_end"), shape.extents, VfxProperty.SHAPE_EXTENTS, vfxText("line_end_hint")) {
                update(shape.copy(extents = it))
            }
        }
        if (shape.kind == VfxShapeKind.MODEL) {
            VfxAssetRow(vfxText("model"), shape.model, VfxModelExtensions, vfxText("shape_model_hint")) {
                update(shape.copy(model = it))
            }
        }

        Text(vfxText("direction_mode"), tags = listOf("insp-inline-label"))
        Pills(VfxDirectionMode.entries, shape.directionMode, { vfxText("aim_${it.name.lowercase()}") }) {
            update(shape.copy(directionMode = it))
        }
        val flat = shape.kind == VfxShapeKind.POINT || shape.kind == VfxShapeKind.BOX || shape.kind == VfxShapeKind.LINE
        val readsDirection = shape.directionMode == VfxDirectionMode.FIXED ||
                shape.directionMode == VfxDirectionMode.SHAPE && flat
        if (readsDirection) {
            VfxFloat3Row(vfxText("direction"), shape.direction) { update(shape.copy(direction = it)) }
        }
    }
}
