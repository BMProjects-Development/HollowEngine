package ru.hollowhorizon.hollowengine.client.ui.ide

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiCompletionContext
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiTextCompletion
import ru.hollowhorizon.hollowengine.client.ui.widgets.completionWordRange
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionCloseness
import ru.hollowhorizon.hollowengine.common.scripting.ide.isKotlinCodeAt
import java.math.BigDecimal
import java.math.RoundingMode

/** What the snippets evaluate to, as scripts see these classes: they compile against Mojang names. */
internal const val PositionSnippetType = "net.minecraft.world.phys.Vec3"
internal const val BlockSnippetType = "net.minecraft.core.BlockPos"
internal const val ItemSnippetType = "net.minecraft.world.item.ItemStack"
internal val ContextSnippetTypes = listOf(PositionSnippetType, BlockSnippetType, ItemSnippetType)

private const val LookReach = 96.0
private const val Places = 2

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
    val look = player.pick(LookReach, 0f, false).takeIf { it.type != HitResult.Type.MISS }?.location
    val stack = player.mainHandItem
    val specification = if (!stack.isEmpty) {
        minecraft.level?.let { level ->
            val ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE)
            val patch =
                DataComponentPatch.CODEC.encodeStart(ops, stack.componentsPatch).result().orElse(null) as? CompoundTag
            patch?.let { itemSpecification(stack.itemHolder.unwrapKey().orElseThrow().location().toString(), it) }
        }
    } else null
    return contextSnippetCompletions(position, specification, stack.count, acceptedTypes, look)
}

internal fun contextSnippetCompletions(
    position: Vec3,
    heldItem: String?,
    count: Int,
    acceptedTypes: List<String> = ContextSnippetTypes,
    look: Vec3? = null,
): List<UiTextCompletion> = buildList {
    if (PositionSnippetType in acceptedTypes) {
        add(contextSnippet("pos", vector(position), "player_position"))
        look?.let { add(contextSnippet("pos", vector(it), "player_look")) }
    }
    if (BlockSnippetType in acceptedTypes) {
        add(blockSnippet(position, "block_position"))
        look?.let { add(blockSnippet(it, "block_look")) }
    }
    if (heldItem != null && ItemSnippetType in acceptedTypes) add(
        contextSnippet(
            "item", "item(\"${heldItem.escapeForRegularKotlinString()}\", $count)", "held_item"
        )
    )
}

/** `pos(x, y, z)` at [at], to a hundredth of a block. */
private fun vector(at: Vec3) = listOf(at.x, at.y, at.z).joinToString(prefix = "pos(", postfix = ")") {
    BigDecimal(it).setScale(Places, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

/** `BlockPos(x, y, z)` of the block [at] is in. */
private fun blockSnippet(at: Vec3, label: String): UiTextCompletion {
    val block = BlockPos.containing(at)
    val insertion = "BlockPos(${block.x}, ${block.y}, ${block.z})"
    return UiTextCompletion(
        label = insertion,
        tail = "hollowengine.gui.ide.editor.completion.$label".lang,
        insertText = insertion,
        filterText = "BlockPos",
        importFqName = BlockSnippetType,
        closeness = CompletionCloseness.LOCAL,
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
