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

/** What the snippets evaluate to, as scripts see these classes: they compile against Mojang names. */
internal const val PositionSnippetType = "net.minecraft.world.phys.Vec3"
internal const val ItemSnippetType = "net.minecraft.world.item.ItemStack"
internal val ContextSnippetTypes = listOf(PositionSnippetType, ItemSnippetType)

/**
 * Snippets built from where the player stands and what they hold, offered where an expression of their type
 * may go. [acceptedTypes] is what the analyzer said fits at the caret, out of [ContextSnippetTypes]; null means
 * no expression starts there. Reads player and world, so it runs on client thread.
 */
internal fun ideContextCompletions(
    path: String,
    context: UiCompletionContext,
    acceptedTypes: List<String>?,
): List<UiTextCompletion> {
    if (acceptedTypes.isNullOrEmpty()) return emptyList()
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
    return contextSnippetCompletions(position, specification, stack.count, acceptedTypes)
}

internal fun contextSnippetCompletions(
    position: Vec3,
    heldItem: String?,
    count: Int,
    acceptedTypes: List<String> = ContextSnippetTypes,
): List<UiTextCompletion> = buildList {
    if (PositionSnippetType in acceptedTypes) add(
        contextSnippet(
            "pos", "pos(${position.x}, ${position.y}, ${position.z})", "player_position"
        )
    )
    if (heldItem != null && ItemSnippetType in acceptedTypes) add(
        contextSnippet(
            "item", "item(\"${heldItem.escapeForRegularKotlinString()}\", $count)", "held_item"
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
