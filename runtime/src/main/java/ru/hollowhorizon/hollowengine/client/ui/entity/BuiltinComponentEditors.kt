package ru.hollowhorizon.hollowengine.client.ui.entity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.client.ui.inspector.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOverlay
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.common.attachments.components.*
import ru.hollowhorizon.hollowengine.common.colliders.fitEntityBox
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager.fromReadablePath
import ru.hollowhorizon.hollowengine.common.models.AnimationPlayMode
import ru.hollowhorizon.hollowengine.common.models.ClipAnimationLayerSpec
import ru.hollowhorizon.hollowengine.common.models.PlayerSkinPart
import ru.hollowhorizon.hollowengine.common.utils.isValidRL
import ru.hollowhorizon.hollowengine.common.utils.rl
import java.util.Locale

/**
 * The extras the engine's own components bring to the editor.
 */
internal object BuiltinComponentEditors {
    private var registered = false

    fun register() {
        if (registered) return
        registered = true

        EditorAssetSources.register(*HollowModelManager.allSupportedFormats.toTypedArray()) { modelPaths() }

        ComponentEditors.register("hollowengine:model".rl) { scope -> ModelExtras(scope) }
        ComponentEditors.register("hollowengine:materials".rl) { scope -> MaterialsExtras(scope) }
        ComponentEditors.register("hollowengine:animations".rl) { scope -> AnimationsExtras(scope) }
        ComponentEditors.register("hollowengine:entity/body".rl) { scope -> BodyExtras(scope) }
    }

    private fun modelPaths(): List<String> = HollowModelManager.allModels.map { it.toString() }
}

/** The model's animator is declared in its `.hemeta`, so the editor can jump straight to that file. */
@Composable
private fun ModelExtras(scope: ComponentEditorScope) {
    val model = scope.component as? Model ?: return
    val animator = model.model.takeIf { it.isValidRL() }?.let { HollowModelManager.metadata(it.rl).animationController }

    if (animator == null) {
        Text(EntityEditorComponentLang.noAnimator, tags = listOf("insp-hint"))
        return
    }

    Text(animator.toString(), tags = listOf("insp-hint"))
    val path = "assets/${animator.namespace}/${animator.path}"
    if (!path.fromReadablePath().isFile) return

    InspectorButton(
        label = EntityEditorComponentLang.editAnimator,
        icon = EntityEditorIcons.STATE,
        modifier = Modifier.size(100.percent, 24.px),
    ) {
        HollowIdeOverlay.openPath(path)
    }
}

/** Sizes the body around the colliders of the entity's model, standing at rest; nothing without colliders. */
@Composable
private fun BodyExtras(scope: ComponentEditorScope) {
    val session = LocalEntityEditorSession.current ?: return
    val body = scope.component as? BodyComponent ?: return
    val modelPath = session.entries.firstNotNullOfOrNull { (it.value as? Model)?.model } ?: return
    val location = remember(modelPath) { ResourceLocation.tryParse(modelPath) } ?: return
    val model by remember(location) { HollowModelManager.getOrCreate(location) }.collectAsState()
    val transform = session.entries.firstNotNullOfOrNull { it.value as? TransformComponent } ?: TransformComponent()
    val fit = remember(model, transform) { fitEntityBox(model, RigAssets.of(location), transform.transform.matrixF) } ?: return

    InspectorButton(
        label = "${EntityEditorComponentLang.fitBody}  ${formatSize(fit.first)} × ${formatSize(fit.second)}",
        modifier = Modifier.size(100.percent, 24.px).tooltipOnHover(EntityEditorComponentLang.fitBodyHint),
    ) {
        ComponentJson.encode(body.copy(width = fit.first, height = fit.second))?.let(scope::replace)
    }
}

private fun formatSize(value: Float): String = "%.2f".format(Locale.ROOT, value)

@Composable
private fun MaterialsExtras(scope: ComponentEditorScope) {
    val materials = (scope.component as? MaterialsComponent)?.materials.orEmpty()
    val missing = listOf(
        MaterialsComponent.SKIN to PlayerSkinPart.SKIN,
        MaterialsComponent.CAPE to PlayerSkinPart.CAPE,
        MaterialsComponent.ELYTRA to PlayerSkinPart.ELYTRA,
    ).filter { it.first !in materials }

    if (missing.isEmpty()) return

    Text(EntityEditorComponentLang.quickMaterials, tags = listOf("insp-label"))
    PillFlow {
        missing.forEach { (name, part) ->
            Pill(name, active = false) {
                val body = scope.json["materials"]?.jsonObject ?: JsonObject(emptyMap())
                val source = ComponentJson.withDiscriminator(
                    JsonObject(
                        mapOf(
                            "player" to JsonPrimitive(Minecraft.getInstance().player?.gameProfile?.name.orEmpty()),
                            "part" to JsonPrimitive(part.name),
                        ),
                    ),
                    "hollowengine:material/player",
                )
                scope.set("materials", body.withField(name, source))
            }
        }
    }
}

/**
 * The clips the entity's own model offers, as switches.
 */
@Composable
private fun AnimationsExtras(scope: ComponentEditorScope) {
    val session = LocalEntityEditorSession.current ?: return
    val component = scope.component as? AnimationsComponent ?: return
    val modelPath = session.entries.firstNotNullOfOrNull { (it.value as? Model)?.model } ?: return
    val location = remember(modelPath) { ResourceLocation.tryParse(modelPath) } ?: return
    val model by remember(location) { HollowModelManager.getOrCreate(location) }.collectAsState()
    val names = model.animations.map { it.name }.sorted()

    if (names.isEmpty()) return

    Text(EntityEditorComponentLang.playAnimation, tags = listOf("insp-label"))
    PillFlow {
        names.forEach { name ->
            val playing = component.clips.any { it.animation == name }
            Pill(name, playing) {
                val next = if (playing) {
                    component.withoutClip(name)
                } else {
                    component.withClip(
                        ClipAnimationLayerSpec(
                            animation = name,
                            playMode = AnimationPlayMode.Loop,
                            startGameTime = Minecraft.getInstance().level?.gameTime,
                        )
                    )
                }
                ComponentJson.encode(next)?.let(scope::replace)
            }
        }
    }
}

internal object EntityEditorComponentLang {
    private const val ROOT = "hollowengine.gui.entity_editor."

    val editAnimator: String get() = ComponentLabels.translate(ROOT + "edit_animator")
    val noAnimator: String get() = ComponentLabels.translate(ROOT + "no_animator")
    val quickMaterials: String get() = ComponentLabels.translate(ROOT + "quick_materials")
    val playAnimation: String get() = ComponentLabels.translate(ROOT + "play_animation")
    val fitBody: String get() = ComponentLabels.translate("hollowengine.component.hollowengine.entity.body.fit")
    val fitBodyHint: String get() = ComponentLabels.translate("hollowengine.component.hollowengine.entity.body.fit.hint")
}
