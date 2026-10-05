package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.ui.Box
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.inspector.Hint
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorIconButton
import ru.hollowhorizon.hollowengine.client.ui.inspector.Pills
import ru.hollowhorizon.hollowengine.client.ui.inspector.ToggleRow
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.onPlaced
import ru.hollowhorizon.hollowengine.client.ui.percent
import ru.hollowhorizon.hollowengine.client.ui.size
import ru.hollowhorizon.hollowengine.client.ui.widgets.ContextMenu
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxCollisionAction
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxCollisionSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxEmitterSpec
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxForceKind
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxForceSpec
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxModuleSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxModuleTypes
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxNoiseSpec
import ru.hollowhorizon.hollowengine.common.vfx.VfxSimulationSpace
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxUvAnimationSpec
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxUvMode
import ru.hollowhorizon.hollowengine.common.vfx.modules.VfxVelocityOverLifetimeSpec
import kotlin.reflect.KClass

/**
 * The modules of an emitter, one folding section each, and a menu of what can still be added.
 */
@Composable
internal fun VfxModuleSections(document: VfxEditing, state: VfxInspectorState, emitter: VfxEmitterSpec) {
    emitter.modules.forEachIndexed { index, module ->
        val type = VfxModuleTypes.of(module)
        val name = type?.titleKey?.lang ?: module.id
        val title = if (module.enabled) name else "$name (${vfxText("off")})"
        Folding(
            state,
            "module-${emitter.id}-${module.id}",
            title,
            moduleIcon(module),
            trailing = {
                InspectorIconButton(
                    VfxIcons.TOGGLE,
                    vfxText("module_enabled"),
                    active = module.enabled,
                    tags = listOf("insp-inline-icon"),
                ) { document.replaceModule(emitter, index, module.withEnabled(!module.enabled)) }
                InspectorIconButton(
                    VfxIcons.REMOVE,
                    vfxText("module_remove"),
                    tags = listOf("insp-inline-icon", "danger"),
                ) { document.removeModule(emitter, index) }
            },
        ) {
            ModuleFields(document, emitter, index, module)
        }
    }

    AddModuleButton(document, emitter)
}

@Composable
private fun AddModuleButton(document: VfxEditing, emitter: VfxEmitterSpec) {
    val addable = VfxModuleTypes.forEmitter(emitter)
        .filter { it.createDefault != null }
        .filter { type -> type.repeatable || emitter.modules.none { type.specClass.isInstance(it) } }
    if (addable.isEmpty()) return

    var open by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(UiRect.Zero) }

    Box(modifier = Modifier.size(100.percent).onPlaced { anchor = it }) {
        InspectorButton(vfxText("module_add"), icon = VfxIcons.ADD) { open = true }
    }
    if (!open) return

    ContextMenu(
        id = "vfx-add-module",
        anchorBounds = anchor,
        items = addable.map { type ->
            UiDropdownItem(type.titleKey.lang, icon = moduleIcon(type.specClass)) {
                type.createDefault?.invoke()?.let { document.addModule(emitter, it) }
            }
        },
        onExpandedChange = { if (!it) open = false },
    )
}

private fun moduleIcon(module: VfxModuleSpec): String = moduleIcon(module::class)

private fun moduleIcon(kind: KClass<out VfxModuleSpec>): String = when (kind) {
    VfxVelocityOverLifetimeSpec::class -> VfxIcons.VELOCITY
    VfxForceSpec::class -> VfxIcons.FORCE
    VfxNoiseSpec::class -> VfxIcons.NOISE
    VfxCollisionSpec::class -> VfxIcons.COLLISION
    VfxUvAnimationSpec::class -> VfxIcons.FLIPBOOK
    else -> VfxIcons.MODULES
}

@Composable
private fun ModuleFields(
    document: VfxEditing,
    emitter: VfxEmitterSpec,
    index: Int,
    module: VfxModuleSpec,
) {
    fun replace(next: VfxModuleSpec) = document.replaceModule(emitter, index, next)
    fun property(field: String) = VfxProperty.module(module.id, field)

    when (module) {
        is VfxVelocityOverLifetimeSpec -> {
            Pills(VfxSimulationSpace.entries, module.space, { vfxText("space_${it.name.lowercase()}") }) {
                replace(module.copy(space = it))
            }
            VfxVec3Row(vfxText("velocity"), module.velocity, property("velocity"), vfxText("velocity_hint")) {
                replace(module.copy(velocity = it))
            }
        }

        is VfxForceSpec -> {
            Pills(VfxForceKind.entries, module.kind, { vfxText("force_${it.name.lowercase()}") }) {
                replace(module.copy(kind = it))
            }
            VfxValueRow(vfxText("strength"), module.strength, property("strength"), vfxText("force_strength_hint")) {
                replace(module.copy(strength = it))
            }
            if (module.kind == VfxForceKind.DIRECTIONAL || module.kind == VfxForceKind.VORTEX) {
                VfxFloat3Row(vfxText("direction"), module.direction) { replace(module.copy(direction = it)) }
            }
            if (module.kind != VfxForceKind.DIRECTIONAL) {
                VfxFloat3Row(vfxText("force_center"), module.center, hint = vfxText("force_center_hint")) {
                    replace(module.copy(center = it))
                }
            }
            VfxValueRow(vfxText("radius"), module.radius, property("radius"), vfxText("force_radius_hint")) {
                replace(module.copy(radius = it))
            }
            ToggleRow(vfxText("falloff"), module.falloff) { replace(module.copy(falloff = it)) }
        }

        is VfxNoiseSpec -> {
            VfxValueRow(vfxText("strength"), module.strength, property("strength")) { replace(module.copy(strength = it)) }
            VfxValueRow(vfxText("frequency"), module.frequency, property("frequency"), vfxText("frequency_hint")) {
                replace(module.copy(frequency = it))
            }
            VfxNumberRow(vfxText("scroll_speed"), module.scrollSpeed, vfxText("scroll_speed_hint")) {
                replace(module.copy(scrollSpeed = it))
            }
            VfxIntRow(vfxText("octaves"), module.octaves, vfxText("octaves_hint"), min = 1, max = 4) {
                replace(module.copy(octaves = it))
            }
        }

        is VfxCollisionSpec -> {
            Pills(VfxCollisionAction.entries, module.action, { vfxText("hit_${it.name.lowercase()}") }) {
                replace(module.copy(action = it))
            }
            if (module.action == VfxCollisionAction.BOUNCE) {
                VfxNumberRow(vfxText("bounce"), module.bounce, vfxText("bounce_hint"), min = 0f, max = 1f) {
                    replace(module.copy(bounce = it))
                }
            }
            if (module.action != VfxCollisionAction.DIE) {
                VfxNumberRow(vfxText("friction"), module.friction, vfxText("friction_hint"), min = 0f, max = 1f) {
                    replace(module.copy(friction = it))
                }
                VfxNumberRow(vfxText("lifetime_loss"), module.lifetimeLoss, vfxText("lifetime_loss_hint"), min = 0f) {
                    replace(module.copy(lifetimeLoss = it))
                }
            }
            VfxNumberRow(vfxText("hit_radius"), module.radius, vfxText("hit_radius_hint"), min = 0f, max = 1f) {
                replace(module.copy(radius = it))
            }
        }

        is VfxUvAnimationSpec -> {
            VfxIntRow(vfxText("uv_columns"), module.columns, min = 1, max = 64) { replace(module.copy(columns = it)) }
            VfxIntRow(vfxText("uv_rows"), module.rows, min = 1, max = 64) { replace(module.copy(rows = it)) }
            VfxIntRow(vfxText("uv_frames"), module.frames, vfxText("uv_frames_hint"), min = 0, max = 4096) {
                replace(module.copy(frames = it))
            }
            Pills(VfxUvMode.entries, module.mode, { vfxText("uv_${it.name.lowercase()}") }) {
                replace(module.copy(mode = it))
            }
            when (module.mode) {
                VfxUvMode.FPS -> VfxNumberRow(vfxText("fps"), module.fps, min = 0f) { replace(module.copy(fps = it)) }
                VfxUvMode.OVER_LIFETIME -> VfxNumberRow(vfxText("cycles"), module.cycles, vfxText("cycles_hint"), min = 0f) {
                    replace(module.copy(cycles = it))
                }

                VfxUvMode.RANDOM_FRAME -> Unit
            }
        }

        else -> Hint(vfxText("module_unknown"))
    }
}

private fun VfxEditing.replaceModule(emitter: VfxEmitterSpec, index: Int, module: VfxModuleSpec) {
    val modules = emitter.modules.toMutableList()
    if (index !in modules.indices) return
    modules[index] = module
    edit(mergeKey = "node:${emitter.id}") { it.withNode(emitter.copy(modules = modules)) }
}

private fun VfxEditing.removeModule(emitter: VfxEmitterSpec, index: Int) {
    val modules = emitter.modules.filterIndexed { at, _ -> at != index }
    edit(mergeKey = "node:${emitter.id}") { it.withNode(emitter.copy(modules = modules)) }
}

private fun VfxEditing.addModule(emitter: VfxEmitterSpec, module: VfxModuleSpec) {
    edit { it.withNode(emitter.copy(modules = emitter.modules + module)) }
}
