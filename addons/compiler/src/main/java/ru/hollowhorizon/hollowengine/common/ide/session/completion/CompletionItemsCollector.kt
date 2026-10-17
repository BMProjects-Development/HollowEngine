package ru.hollowhorizon.hollowengine.common.ide.session.completion

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.components.KaCompletionExtensionCandidateChecker
import org.jetbrains.kotlin.analysis.api.components.KaExtensionApplicabilityResult
import org.jetbrains.kotlin.analysis.api.components.KaUseSiteVisibilityChecker
import org.jetbrains.kotlin.analysis.api.signatures.KaCallableSignature
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol
import org.jetbrains.kotlin.analysis.api.symbols.name
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtDeclaration
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionItem
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionItemTag
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionCloseness
import ru.hollowhorizon.hollowengine.common.scripting.ide.CompletionSink
import ru.hollowhorizon.hollowengine.common.scripting.ide.DeclarationCompletionItemBuilder
import ru.hollowhorizon.hollowengine.common.scripting.ide.declarationCompletionItem
import kotlin.contracts.contract

/**
 * Thrown through the collection call stack when the [CompletionSink] refuses a batch, which means
 * the editor moved on and nothing this request still has to offer will be shown.
 */
internal class CompletionCancelledException : RuntimeException(null, null, false, false)

internal class CompletionItemsCollector(
    private val applicabilityChecker: KaCompletionExtensionCandidateChecker?,
    private val visibilityChecker: KaUseSiteVisibilityChecker?,
    val nameFilter: (Name) -> Boolean,
    val symbolFilter: SymbolFilter,
    private val sink: CompletionSink = CompletionSink { true },
    private val insertCallSyntax: Boolean = true,
) {
    fun interface SymbolFilter {
        context(_: KaSession) fun accepts(symbol: KaDeclarationSymbol): Boolean
    }

    private val factory: CompletionItemFactory = CompletionItemFactory

    private val items = mutableListOf<CompletionItem>()
    private val symbols = mutableSetOf<KaDeclarationSymbol>()
    private val seen = mutableSetOf<CompletionItem>()

    /** Closeness handed to everything collected until [phase] is called again. */
    var closeness: Int = CompletionCloseness.DEFAULT
        private set

    /**
     * Starts a collection phase: items found from here on are [closeness] close to the caret, and
     * whatever the previous phase found is pushed to the sink so the popup can show it right away.
     */
    fun phase(closeness: Int) {
        flush()
        this.closeness = closeness
    }

    /** Publishes everything buffered so far; throws [CompletionCancelledException] when refused. */
    fun flush() {
        if (items.isEmpty()) return
        val batch = items.toList()
        items.clear()
        if (!sink.emit(batch)) throw CompletionCancelledException()
    }

    context(kaSession: KaSession)
    fun add(declaration: KtDeclaration?, modify: (DeclarationCompletionItemBuilder.() -> Unit)? = null) {
        with(kaSession) { add(declaration?.symbol, modify) }
    }

    context(_: KaSession)
    @JvmName("addDeclarations")
    fun add(declarations: Iterable<KtDeclaration>, modify: (DeclarationCompletionItemBuilder.() -> Unit)? = null) {
        declarations.forEach { add(it, modify) }
    }

    context(_: KaSession)
    fun add(symbol: KaDeclarationSymbol?, modify: (DeclarationCompletionItemBuilder.() -> Unit)? = null) {
        when (symbol) {
            null -> {}
            in symbols -> {}

            is KaCallableSymbol -> {
                val substituted = symbol.asApplicableSignature()
                if (substituted != null) {
                    add(substituted, modify)
                } else if (!symbol.isExtension) {
                    _add(symbol, modify)
                }
            }

            else -> {
                _add(symbol, modify)
            }
        }
    }

    context(_: KaSession)
    @JvmName("addSymbols")
    fun add(symbols: Sequence<KaDeclarationSymbol>, modify: (DeclarationCompletionItemBuilder.() -> Unit)? = null) {
        symbols.forEach { add(it, modify) }
    }

    context(_: KaSession)
    fun add(signature: KaCallableSignature<*>?, modify: (DeclarationCompletionItemBuilder.() -> Unit)? = null) {
        _add(signature, modify)
    }

    context(_: KaSession)
    @JvmName("addSignatures")
    fun add(symbols: Sequence<KaCallableSignature<*>>, modify: (DeclarationCompletionItemBuilder.() -> Unit)? = null) {
        symbols.forEach { add(it, modify) }
    }

    fun add(items: List<CompletionItem>) {
        items.forEach(::add)
    }

    fun add(item: CompletionItem) {
        if (!seen.add(item.deduplicationKey())) return
        items += item
        if (items.size >= FlushThreshold) flush()
    }

    context(_: KaSession)
    @Suppress("FunctionName")
    private fun _add(symbol: KaDeclarationSymbol?, modify: (DeclarationCompletionItemBuilder.() -> Unit)?) {
        if (!acceptsSymbol(symbol)) return
        val item = factory.createCompletionItem(symbol, this@CompletionItemsCollector.closeness)?.withCallSyntax() ?: return
        symbols += symbol
        add(item.applying(modify))
    }

    context(_: KaSession)
    @Suppress("FunctionName")
    private fun _add(signature: KaCallableSignature<*>?, modify: (DeclarationCompletionItemBuilder.() -> Unit)?) {
        if (!acceptsSymbol(signature?.symbol)) return

        val item = factory.createCompletionItem(signature, this@CompletionItemsCollector.closeness)?.withCallSyntax() ?: return
        symbols += signature.symbol
        add(item.applying(modify))
    }

    private fun CompletionItem.Declaration.applying(
        modify: (DeclarationCompletionItemBuilder.() -> Unit)?,
    ): CompletionItem.Declaration {
        if (modify == null) return this
        return declarationCompletionItem {
            with(this@applying)
            modify()
        }
    }

    private fun CompletionItem.Declaration.withCallSyntax(): CompletionItem.Declaration =
        if (!insertCallSyntax && tag == CompletionItemTag.FUNCTION) copy(insert = name, moveCaret = 0) else this

    context(_: KaSession)
    private fun acceptsSymbol(symbol: KaDeclarationSymbol?): Boolean {
        contract { returns(true) implies (symbol != null) }
        runCatching {
            if (symbol == null) return false
            if (symbol in symbols) return false
            if (visibilityChecker?.isVisible(symbol) == false) return false

            if (symbol.name?.asString()?.contains(COMPLETION_FAKE_IDENTIFIER) == true) return false
            if (!symbolFilter.accepts(symbol)) return false

            return true
        }

        return false
    }

    context(kaSession: KaSession)
    private fun KaCallableSymbol.asApplicableSignature(): KaCallableSignature<KaCallableSymbol>? = with(kaSession) {
        val checker = applicabilityChecker ?: return asSignature()
        return runCatching {
            when (val applicability = checker.computeApplicability(this@asApplicableSignature)) {
                is KaExtensionApplicabilityResult.Applicable -> substitute(applicability.substitutor)
                else -> null
            }
        }.getOrNull()
    }

}

private const val FlushThreshold = 48

private fun CompletionItem.deduplicationKey(): CompletionItem = when (this) {
    is CompletionItem.Declaration -> copy(import = false, closeness = 0)
    is CompletionItem.Keyword -> copy(closeness = 0)
}
