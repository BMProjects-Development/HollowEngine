package ru.hollowhorizon.hollowengine.client.ui.ide

import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContext
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextCompletion
import ru.hollowhorizon.hollowengine.client.ui.widgets.completionWordRange
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionCloseness
import ru.hollowhorizon.hollowengine.common.scripting.ide.isKotlinCodeAt

internal fun ideContextCompletions(path: String, context: UiCompletionContext): List<UiTextCompletion> {
    if (!path.isKotlinSource() || !context.text.isKotlinCodeAt(context.caret)) return emptyList()
    val word = completionWordRange(context.text, context.caret)
    val suffix = context.text.substring(word.last).trimStart()
    if (suffix.startsWith('(') || suffix.startsWith('{') || context.text.substring(0, word.first).trimEnd()
            .endsWith("::")
    ) return emptyList()
    val minecraft = Minecraft.getInstance()
    val player = minecraft.player ?: return emptyList()
    val position = player.position()
    val stack = player.mainHandItem
    val specification = if (!stack.isEmpty) {
        minecraft.level?.let { level ->
            val ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE)
            val patch =
                DataComponentPatch.CODEC.encodeStart(ops, stack.componentsPatch).result().orElse(null) as? CompoundTag
            patch?.let { itemSpecification(stack.itemHolder.unwrapKey().orElseThrow().location().toString(), it) }
        }
    } else null
    return contextSnippetCompletions(position, specification, stack.count)
}

internal fun contextSnippetCompletions(position: Vec3, heldItem: String?, count: Int): List<UiTextCompletion> =
    buildList {
        add(
            contextSnippet(
                "pos",
                "pos(${position.x}, ${position.y}, ${position.z})",
                "player_position"
            )
        )
        if (heldItem != null) add(
            contextSnippet(
                "item",
                "item(\"${heldItem.escapeForRegularKotlinString()}\", $count)",
                "held_item"
            )
        )
    }

private fun contextSnippet(name: String, insertion: String, label: String) = UiTextCompletion(
    label = insertion,
    tail = "hollowengine.gui.ide.editor.completion.$label".lang,
    insertText = insertion,
    filterText = name,
    importFqName = "ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs.$name",
    closeness = CompletionCloseness.LOCAL,
)

internal fun itemSpecification(id: String, patch: CompoundTag): String {
    if (patch.isEmpty) return id
    return patch.allKeys.sorted().joinToString(",", "$id[", "]") { key ->
        if (key.startsWith('!')) key else "$key=${patch.get(key)}"
    }
}
