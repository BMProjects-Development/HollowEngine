package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ru.hollowhorizon.hollowengine.client.ui.Checkbox
import ru.hollowhorizon.hollowengine.client.ui.HollowUiContent
import ru.hollowhorizon.hollowengine.client.ui.Image
import ru.hollowhorizon.hollowengine.client.ui.Modifier
import ru.hollowhorizon.hollowengine.client.ui.Row
import ru.hollowhorizon.hollowengine.client.ui.TextField
import ru.hollowhorizon.hollowengine.client.ui.UiLength
import ru.hollowhorizon.hollowengine.client.ui.grow
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeVfxDocument
import ru.hollowhorizon.hollowengine.client.ui.inspector.CollapsibleSection
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorTarget
import ru.hollowhorizon.hollowengine.client.ui.inspector.Readonly
import ru.hollowhorizon.hollowengine.client.ui.inspector.ToggleRow
import ru.hollowhorizon.hollowengine.client.ui.px
import ru.hollowhorizon.hollowengine.client.ui.size
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.vfx.*
import ru.hollowhorizon.hollowengine.client.history.UndoLabel

/** What the shared inspector shows for the selected node of an effect. */
internal fun vfxNodeInspector(
    document: HollowIdeVfxDocument,
    state: VfxEditorState,
    nodeId: String,
): InspectorTarget {
    val node = document.effect.node(nodeId)
    val type = node?.let(VfxNodeTypes::of)
    return InspectorTarget(
        id = "vfx-node-$nodeId",
        title = node?.name ?: nodeId,
        subtitle = type?.titleKey?.lang,
        icon = type?.icon,
    ) {
        val live = document.effect.node(nodeId) ?: return@InspectorTarget
        CompositionLocalProvider(
            LocalVfxFieldFocus provides { property -> state.focusProperty(nodeId, property) },
            LocalVfxDriven provides vfxDrivenLookup(document, state, nodeId),
        ) {
            VfxNodeFields(document, state, live)
        }
    }
}

internal fun VfxEditing.replace(node: VfxNodeSpec) = edit(mergeKey = "node:${node.id}", label = UndoLabel("${UndoLabel.LANG}.vfx.node", node.id)) { it.withNode(node) }

/** Everything the inspector shows for [node], wherever the effect it belongs to lives. */
@Composable
internal fun VfxNodeFields(document: VfxEditing, state: VfxInspectorState, node: VfxNodeSpec) {
    NodeHead(document, node)

    Folding(state, "transform", vfxText("section_transform"), VfxIcons.TRANSFORM) {
        val transform = node.transform
        VfxFloat3Row(vfxText("position"), transform.position, VfxProperty.POSITION) {
            document.replace(node.withCommon(transform = transform.copy(position = it)))
        }
        VfxFloat3Row(vfxText("rotation"), transform.rotation, VfxProperty.ROTATION) {
            document.replace(node.withCommon(transform = transform.copy(rotation = it)))
        }
        VfxFloat3Row(vfxText("scale"), transform.scale, VfxProperty.SCALE) {
            document.replace(node.withCommon(transform = transform.copy(scale = it)))
        }
    }

    if (node is VfxParticleRendererSpec && document.effect.parentOf(node.id) is VfxEmitterSpec) {
        ParticleFields(document, state, node)
    }

    when (node) {
        is VfxEmitterSpec -> EmitterFields(document, state, node, underEmitter = document.effect.parentOf(node.id) is VfxEmitterSpec)
        is VfxPlaneSpec -> PlaneFields(document, state, node)
        is VfxMeshSpec -> MeshFields(document, state, node)
        is VfxModelSpec -> ModelFields(document, state, node)
        is VfxTrailSpec -> TrailFields(document, state, node)
        is VfxBeamSpec -> BeamFields(document, state, node)
        is VfxPostEffectSpec -> PostEffectFields(document, state, node)
        is VfxSkySpec -> SkyFields(document, state, node)
        is VfxCameraShakeSpec -> CameraShakeFields(document, state, node)
        else -> Unit
    }
}

/**
 * Whether the node is on and what it is called, on one line above the sections; a renderer also says
 * whether it draws on particles or once, which depends on where it sits in the tree.
 */
@Composable
private fun NodeHead(document: VfxEditing, node: VfxNodeSpec) {
    var draft by remember(node.id, node.name) { mutableStateOf(node.name) }
    Row(tags = listOf("vfx-node-head")) {
        Checkbox(
            checked = node.enabled,
            id = "vfx-node-enabled-${node.id}",
            tags = listOf("insp-checkbox"),
            modifier = Modifier.tooltipOnHover(vfxText("enabled")),
            onCheckedChange = { document.replace(node.withCommon(enabled = it)) },
        )
        TextField(
            value = draft,
            id = "vfx-node-name-${node.id}",
            fontSize = 9f,
            placeholder = vfxText("name"),
            tags = listOf("insp-input", "insp-inline-input"),
            modifier = Modifier.size(0.px, UiLength.Fit).grow(1f),
            onChange = { typed ->
                draft = typed
                val trimmed = typed.trim()
                if (trimmed.isNotEmpty() && trimmed != node.name) document.replace(node.withCommon(name = trimmed))
            },
        )
        if (node is VfxParticleRendererSpec) {
            val onParticles = document.effect.parentOf(node.id) is VfxEmitterSpec
            Image(
                if (onParticles) VfxIcons.PARTICLE else VfxIcons.SPAWN_ONCE,
                tags = listOfNotNull("vfx-placement", "active".takeIf { onParticles }),
                modifier = Modifier.tooltipOnHover(vfxText(if (onParticles) "renders_particles" else "renders_once")),
            )
        }
    }
}

/**
 * What a renderer gives each particle of its emitter. A trail has a width of its own and does not
 * turn, so it only offers the color.
 */
@Composable
private fun ParticleFields(document: VfxEditing, state: VfxInspectorState, node: VfxParticleRendererSpec) {
    val look = node.particle
    fun update(next: VfxAppearance) = document.replace(node.withParticle(next))

    Folding(state, "particle", vfxText("section_particle"), VfxIcons.PARTICLE) {
        if (node !is VfxTrailSpec) {
            ToggleRow(vfxText("uniform_size"), look.uniformSize) { update(look.copy(uniformSize = it)) }
            VfxVec3Row(vfxText("size"), look.size, VfxProperty.SIZE, vfxText("size_hint"), uniform = look.uniformSize) {
                update(look.copy(size = it))
            }
            VfxVec3Row(vfxText("spin"), look.rotation, VfxProperty.SPIN, vfxText("spin_hint")) {
                update(look.copy(rotation = it))
            }
        }
        VfxColorRow(vfxText("color"), look.color, VfxProperty.COLOR, vfxText("color_hint")) {
            update(look.copy(color = it))
        }
    }
}

/**
 * A section that remembers whether it is open per effect editor. Every section starts closed, so a
 * node opens as a list of what it has rather than a wall of fields.
 */
@Composable
internal fun Folding(
    state: VfxInspectorState,
    key: String,
    title: String,
    icon: String,
    trailing: HollowUiContent? = null,
    content: @Composable () -> Unit,
) {
    CollapsibleSection(
        title = title,
        expanded = state.isSectionOpen(key),
        id = "vfx-section-$key",
        icon = icon,
        trailing = trailing,
        onToggle = { state.toggleSection(key) },
    ) {
        content()
    }
}

/** What the inspector shows for the effect itself, the root of the tree. */
internal fun vfxEffectInspector(document: HollowIdeVfxDocument, state: VfxEditorState): InspectorTarget =
    InspectorTarget(id = "vfx-effect", title = vfxText("effect"), icon = VfxIcons.EFFECT) {
        val effect = document.effect
        Folding(state, "timeline", vfxText("section_timeline"), VfxIcons.TIMELINE) {
            VfxNumberRow(
                vfxText("timeline_duration"),
                effect.timeline.duration,
                vfxText("timeline_duration_hint"),
                min = 0.1f
            ) { duration ->
                document.edit { it.copy(timeline = it.timeline.copy(duration = duration)) }
            }
            ToggleRow(vfxText("timeline_loop"), effect.timeline.loop) { loop ->
                document.edit { it.copy(timeline = it.timeline.copy(loop = loop)) }
            }
        }
        Folding(state, "stats", vfxText("section_stats"), VfxIcons.STATS) {
            Readonly(vfxText("nodes"), effect.walk().size.toString())
            Readonly(vfxText("expressions"), effect.expressions().size.toString())
        }
    }

/** The icons of the effect editor, one family drawn for it. */
internal object VfxIcons {
    private const val ROOT = "hollowengine:textures/gui/icons/vfx/"

    const val EFFECT = ROOT + "node_effect.svg"
    const val TRANSFORM = ROOT + "transform.svg"
    const val PARTICLE = ROOT + "particle.svg"
    const val SPAWN_ONCE = ROOT + "key.svg"
    const val EMISSION = ROOT + "emission.svg"
    const val SUB_EMITTER = ROOT + "sub_emitter.svg"
    const val SHAPE = ROOT + "shape.svg"
    const val SPAWN = ROOT + "spawn.svg"
    const val MOTION = ROOT + "motion.svg"
    const val SPACE = ROOT + "space.svg"
    const val MODULES = ROOT + "modules.svg"
    const val FORCE = ROOT + "force.svg"
    const val NOISE = ROOT + "noise.svg"
    const val VELOCITY = ROOT + "velocity.svg"
    const val COLLISION = ROOT + "collision.svg"
    const val FLIPBOOK = ROOT + "flipbook.svg"
    const val FACING = ROOT + "facing.svg"
    const val MESH = ROOT + "mesh.svg"
    const val MODEL = ROOT + "model.svg"
    const val MATERIAL = ROOT + "material.svg"
    const val SHADER = ROOT + "shader.svg"
    const val TRAIL = ROOT + "trail.svg"
    const val BEAM = ROOT + "beam.svg"
    const val POST = ROOT + "post.svg"
    const val SKY = ROOT + "sky.svg"
    const val SHAKE = ROOT + "shake.svg"
    const val TIMELINE = ROOT + "timeline.svg"
    const val STATS = ROOT + "stats.svg"
    const val ADD = ROOT + "add.svg"
    const val REMOVE = ROOT + "remove.svg"
    const val DUPLICATE = ROOT + "duplicate.svg"
    const val RENAME = ROOT + "rename.svg"
    const val TOGGLE = ROOT + "toggle.svg"
    const val MORE = ROOT + "more.svg"
}
