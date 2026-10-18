package ru.hollowhorizon.hollowengine.client.ui.ide

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import ru.hollowhorizon.hollowengine.common.scripting.ide.HoverInfo
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptingAnalyzer
import ru.hollowhorizon.hollowengine.common.scripting.ide.SignatureHelp

/** Asynchronous, latest-request-wins cache for caret and pointer code insight. */
internal class HollowIdeCodeInsightSession(
    private val path: String,
    private val scope: CoroutineScope,
    private val currentAnalyzer: () -> ScriptingAnalyzer,
    private val publish: () -> Unit,
    private val reportFailure: (String, Throwable) -> Unit,
) {
    @Volatile
    private var requestedSignature: InsightKey? = null
    @Volatile
    private var requestedHover: InsightKey? = null
    @Volatile
    private var signatureSnapshot = SignatureSnapshot.Empty
    @Volatile
    private var hoverSnapshot = HoverSnapshot.Empty
    private var signatureJob: Job? = null
    private var hoverJob: Job? = null

    val signatures = object : UiDeferredSignatureHelpProvider {
        override fun query(context: UiCompletionContext): UiSignatureHelpResult? {
            val analyzer = currentAnalyzer()
            val key = InsightKey.of(context, analyzer)
            requestSignature(context, key, analyzer)
            return signatureSnapshot.takeIf { it.key == key }?.let { UiSignatureHelpResult(it.help) }
        }
    }

    val hover = UiHoverInfoProvider { context ->
        val analyzer = currentAnalyzer()
        val key = InsightKey.of(context, analyzer)
        requestHover(context, key, analyzer)
        hoverSnapshot.takeIf { it.key == key }?.info
    }

    private fun requestSignature(context: UiCompletionContext, key: InsightKey, analyzer: ScriptingAnalyzer) {
        if (requestedSignature == key) return
        requestedSignature = key
        signatureJob?.cancel()
        signatureJob = scope.launch {
            val help = runCatching {
                analyzer.signatureHelp(path, context.text, key.offset)?.toUi()
            }.getOrElse { failure ->
                reportFailure("signature-help", failure)
                null
            }
            ensureActive()
            if (requestedSignature != key) return@launch
            signatureSnapshot = SignatureSnapshot(key, help)
            publish()
        }
    }

    private fun requestHover(context: UiCompletionContext, key: InsightKey, analyzer: ScriptingAnalyzer) {
        if (requestedHover == key) return
        requestedHover = key
        hoverJob?.cancel()
        hoverJob = scope.launch {
            val info = runCatching {
                analyzer.hover(path, context.text, key.offset)?.toUi()
            }.getOrElse { failure ->
                reportFailure("hover", failure)
                null
            }
            ensureActive()
            if (requestedHover != key) return@launch
            hoverSnapshot = HoverSnapshot(key, info)
            publish()
        }
    }
}

private data class InsightKey(
    val textHash: Int,
    val textLength: Int,
    val offset: Int,
    val analyzer: ScriptingAnalyzer,
) {
    companion object {
        fun of(context: UiCompletionContext, analyzer: ScriptingAnalyzer) = InsightKey(
            context.text.hashCode(),
            context.text.length,
            context.caret.coerceIn(0, context.text.length),
            analyzer,
        )
    }
}

private data class SignatureSnapshot(val key: InsightKey?, val help: UiTextSignatureHelp?) {
    companion object {
        val Empty = SignatureSnapshot(null, null)
    }
}

private data class HoverSnapshot(val key: InsightKey?, val info: UiTextHoverInfo?) {
    companion object {
        val Empty = HoverSnapshot(null, null)
    }
}

private fun SignatureHelp.toUi() = UiTextSignatureHelp(
    anchor = anchor,
    signatures = signatures.map { signature ->
        UiTextSignature(
            label = signature.label,
            parameters = signature.parameters.map { it.start until it.end },
            documentation = signature.documentation,
            highlights = signature.highlights.map { highlight ->
                UiCodeInsightHighlight(
                    range = highlight.range.start until highlight.range.end,
                    tokenType = highlight.tokenType,
                )
            },
            presentation = signature.presentation.start until signature.presentation.end,
        )
    },
    activeSignature = activeSignature,
    activeParameter = activeParameter,
)

private fun HoverInfo.toUi() = UiTextHoverInfo(
    start = start,
    end = end,
    signature = signature,
    documentation = documentation,
    highlights = highlights.map { highlight ->
        UiCodeInsightHighlight(
            range = highlight.range.start until highlight.range.end,
            tokenType = highlight.tokenType,
        )
    },
)
