package ru.hollowhorizon.hollowengine.common.commands

import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.models.ServerModelAnimationMetadata
import ru.hollowhorizon.hollowengine.common.npcs.NpcAnimationRuntime
import ru.hollowhorizon.hollowengine.common.utils.*
import java.util.*

/**
 * Lists a model's animation clips and materials as command feedback. The server reads the model
 * itself, so the answer reaches whoever ran the command, a console or a tool included.
 */
internal fun showModelInfo(source: CommandSourceStack, model: String): Int {
    val loaded = ServerModelAnimationMetadata.model(model)
    if (loaded == null) {
        source.sendFailure("hollowengine.commands.model_unreadable".mcTranslate(model))
        return 0
    }
    source.sendSuccess({ "hollowengine.commands.model_animations".mcTranslate(model) }, false)
    loaded.animations.sortedBy { it.name }.forEach { clip ->
        source.sendSuccess({ entry(clip.name, " (%.2f s)".format(Locale.ROOT, clip.duration)) }, false)
    }
    source.sendSuccess({ "hollowengine.commands.model_materials".mcTranslate(model) }, false)
    loaded.materials.sortedBy { it.name }.forEach { material ->
        source.sendSuccess({ entry(material.name, " → ${material.texture}") }, false)
    }
    return loaded.animations.size
}

/** [showModelInfo] for the model [entity] shows. */
internal fun showEntityModel(source: CommandSourceStack, entity: Entity): Int {
    val model = NpcAnimationRuntime.modelOf(entity)
    if (model == null) {
        source.sendFailure("hollowengine.commands.entity_without_model".mcTranslate(entity.name))
        return 0
    }
    return showModelInfo(source, model)
}

/** Refuses a clip [entity]'s model does not have, naming the ones it does. Null when it may play. */
internal fun missingAnimation(entity: Entity, animation: String): Component? {
    val clips = NpcAnimationRuntime.animationNames(entity) ?: return null
    if (animation in clips) return null
    return "hollowengine.commands.model_no_animation".mcTranslate(
        NpcAnimationRuntime.modelOf(entity).orEmpty(), animation, clips.sorted().joinToString(),
    )
}

private fun entry(name: String, detail: String) =
    ("- ".literal + name.literal + detail.literal)
        .onHoverText("hollowengine.tooltips.copy".mcTranslate)
        .onClickCopy(name)
