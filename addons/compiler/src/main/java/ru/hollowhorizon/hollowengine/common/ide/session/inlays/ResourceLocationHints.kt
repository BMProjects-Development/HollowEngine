package ru.hollowhorizon.hollowengine.common.ide.session.inlays

import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import ru.hollowhorizon.hollowengine.common.scripting.ide.*

/**
 * Turns `"project:ui/style.hss"` in a script into a button that opens the file it names.
 * Resources the project owns open for editing; those served by the game open read-only,
 * which the hint says through its tags rather than through a different button.
 */
fun resourceLocationHints(file: KtFile, project: ScriptAnalysisProject?): List<InlayHint> {
    val hints = mutableListOf<InlayHint>()
    for (node in file.preOrderTraversal()) {
        val literal = node as? KtStringTemplateExpression ?: continue
        val location = literal.plainText() ?: continue
        if (!ResourceLocationTargets.looksLikeLocation(location)) continue
        val target = ResourceLocationTargets.targetOf(location, project) ?: continue
        hints += InlayHint(
            index = literal.startOffset,
            content = listOf(InlayContent.Icon(InlayIcons.OPEN_RESOURCE)),
            tags = listOf(InlayTags.ACTION, target.tag),
            action = InlayAction.OpenResource(location),
        )
    }
    return hints
}

/** The literal's text, or `null` when it has interpolation and thus no fixed value. */
private fun KtStringTemplateExpression.plainText(): String? {
    if (hasInterpolation()) return null
    val entries = entries
    if (entries.isEmpty()) return null
    return entries.joinToString("") { it.text }
}

/** Definition target of the location literal at [offset], if the caret sits on one. */
fun resourceLocationDefinition(file: KtFile, offset: Int, project: ScriptAnalysisProject?) =
    file.locationLiteralAt(offset)?.let { ResourceLocationTargets.definition(it, project) }

private fun KtFile.locationLiteralAt(offset: Int): String? {
    if (textLength == 0) return null
    val safeOffset = offset.coerceIn(0, textLength - 1)
    val literal = literalAt(safeOffset) ?: literalAt(safeOffset - 1) ?: return null
    val text = literal.plainText() ?: return null
    return text.takeIf(ResourceLocationTargets::looksLikeLocation)
}

private fun KtFile.literalAt(offset: Int): KtStringTemplateExpression? {
    if (offset < 0) return null
    val element = findElementAt(offset) ?: return null
    return generateSequence(element) { it.parent }.filterIsInstance<KtStringTemplateExpression>().firstOrNull()
}
