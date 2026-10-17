package ru.hollowhorizon.hollowengine.common.scripting.ide

sealed interface CompletionItem {
    val show: String
    val insert: String
    val tag: CompletionItemTag
    val moveCaret: Int
    val name: String

    /**
     * Punctuation that is part of the name being completed, beyond letters, digits and `_`. Kotlin
     * needs none; a story command is typed as `@play-video`, so both `@` and `-` belong to the word
     * the editor replaces rather than sitting in front of it.
     */
    val wordChars: String get() = ""

    /** How close the declaration is to the caret; see [CompletionCloseness]. Higher wins ties. */
    val closeness: Int get() = CompletionCloseness.DEFAULT

    data class Declaration(
        override val show: String,
        override val insert: String,
        override val name: String,
        override val tag: CompletionItemTag,
        override val moveCaret: Int = 0,
        val import: Boolean = false,
        val fqName: String?,
        val tail: String?,
        val middle: String?,
        val itemIcon: String? = null,
        override val wordChars: String = "",
        override val closeness: Int = CompletionCloseness.DEFAULT,
    ) : CompletionItem

    data class Keyword(
        override val show: String,
        override val insert: String = show,
        override val name: String,
        override val moveCaret: Int = 0,
        override val wordChars: String = "",
        override val closeness: Int = CompletionCloseness.KEYWORD,
    ) : CompletionItem {
        override val tag: CompletionItemTag
            get() = CompletionItemTag.KEYWORD
    }
}

object CompletionCloseness {
    /** Locals and parameters of the enclosing function. */
    const val LOCAL = 50

    /** Members reachable through an explicit or implicit receiver. */
    const val MEMBER = 40

    /** Anything else already visible: file declarations, imports, default imports. */
    const val SCOPE = 30

    const val KEYWORD = 20
    const val DEFAULT = 10

    /** Index results that only become usable after an import is added. */
    const val IMPORTED = 0
}

class KeywordItemBuilder {
    lateinit var textToShow: String
    lateinit var textToInsert: String
    var moveCaret: Int = 0
    lateinit var name: String
    var wordChars: String = ""
    var closeness: Int = CompletionCloseness.KEYWORD

    fun with(completionItem: CompletionItem.Keyword) {
        textToShow = completionItem.show
        textToInsert = completionItem.insert
        moveCaret = completionItem.moveCaret
        name = completionItem.name
        wordChars = completionItem.wordChars
        closeness = completionItem.closeness
    }

    fun build() = CompletionItem.Keyword(textToShow, textToInsert, name, moveCaret, wordChars, closeness)
}

class DeclarationCompletionItemBuilder {
    lateinit var show: String
    var insert: String? = null
    lateinit var tag: CompletionItemTag
    var moveCaret: Int = 0
    var import: Boolean = false
    var fqName: String? = null
    var name: String? = null
    var tail: String? = null
    var middle: String? = null
    var itemIcon: String? = null
    var wordChars: String = ""
    var closeness: Int = CompletionCloseness.DEFAULT

    fun withImport() {
        import = true
        closeness = CompletionCloseness.IMPORTED
    }

    fun with(completionItem: CompletionItem.Declaration) {
        show = completionItem.show
        insert = completionItem.insert
        tag = completionItem.tag
        moveCaret = completionItem.moveCaret
        import = completionItem.import
        fqName = completionItem.fqName
        name = completionItem.name
        tail = completionItem.tail
        middle = completionItem.middle
        itemIcon = completionItem.itemIcon
        closeness = completionItem.closeness
    }

    fun build() =
        CompletionItem.Declaration(
            show = show,
            insert = insert ?: show,
            name = name ?: fqName?.substringAfterLast(".") ?: error("No name provided"),
            tag = tag,
            moveCaret = moveCaret,
            import = import,
            fqName = fqName,
            tail = tail,
            middle = middle,
            itemIcon = itemIcon,
            wordChars = wordChars,
            closeness = closeness,
        )
}

fun declarationCompletionItem(block: DeclarationCompletionItemBuilder.() -> Unit): CompletionItem.Declaration {
    val builder = DeclarationCompletionItemBuilder().apply(block)
    return builder.build()
}

fun keywordCompletionItem(block: KeywordItemBuilder.() -> Unit = {}): CompletionItem.Keyword {
    val builder = KeywordItemBuilder().apply { apply(block) }
    return builder.build()
}

enum class CompletionItemTag(val text: String) {
    FUNCTION("ⓕ"),
    PROPERTY("ⓟ"),
    CLASS("ⓒ"),
    LOCAL_VARIABLE("ⓥ"),
    KEYWORD("ⓚ"),
}
