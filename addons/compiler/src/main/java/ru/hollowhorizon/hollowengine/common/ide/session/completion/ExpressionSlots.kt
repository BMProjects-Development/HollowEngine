package ru.hollowhorizon.hollowengine.common.ide.session.completion

import com.intellij.psi.util.parentOfType
import org.jetbrains.kotlin.analysis.api.analyzeCopy
import org.jetbrains.kotlin.analysis.api.projectStructure.KaDanglingFileResolutionMode
import org.jetbrains.kotlin.analysis.api.types.KaErrorType
import org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import ru.hollowhorizon.hollowengine.common.ide.session.ScriptingAnalyzerImpl

/**
 * Which of [types] an expression typed at [offset] may have. Null when [offset] is not where an expression
 * starts: after a dot, in a declaration name, in an import or a type. Where nothing is expected, or where the
 * value is thrown away as a statement, every candidate fits.
 */
fun ScriptingAnalyzerImpl.findAcceptedExpressionTypes(file: KtFile, offset: Int, types: List<String>): List<String>? {
    val safeOffset = offset.coerceIn(0, file.textLength)
    val copy = createFileForCompletion(file, safeOffset)
    try {
        val reference = copy.findElementAt(safeOffset)?.parentOfType<KtNameReferenceExpression>(withSelf = true)
            ?: return null
        val call = reference.parent as? KtCallExpression
        val expression: KtExpression = if (call?.calleeExpression == reference) call else reference

        return analyzeCopy(expression, KaDanglingFileResolutionMode.PREFER_SELF) {
            if (getPosition(reference) !is CompletionPosition.Identifier) return@analyzeCopy null
            val expected = expression.expectedType
            if (expected == null || expected is KaErrorType || expected is KaTypeParameterType || expected.isUnitType) {
                return@analyzeCopy types
            }
            types.filter { fqName -> buildClassType(ClassId.topLevel(FqName(fqName))).isSubtypeOf(expected) }
        }
    } finally {
        cleanupFile(copy)
    }
}
