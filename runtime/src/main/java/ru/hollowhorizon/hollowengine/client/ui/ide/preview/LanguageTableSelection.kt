package ru.hollowhorizon.hollowengine.client.ui.ide.preview

import net.minecraft.locale.Language

internal data class LanguageTableSelection(val source: String, val target: String? = null) {
    fun selectSource(code: String): LanguageTableSelection =
        if (code == target) LanguageTableSelection(code, source) else copy(source = code)

    fun selectTarget(code: String): LanguageTableSelection = when {
        code != source -> copy(target = code)
        target != null -> LanguageTableSelection(target, code)
        else -> this
    }

    companion object {
        fun initial(opened: String, gameLanguage: String, available: Set<String>): LanguageTableSelection {
            val alternative = gameLanguage.takeIf { it != opened && it in available }
            return when {
                opened == Language.DEFAULT -> LanguageTableSelection(opened, alternative)
                Language.DEFAULT in available -> LanguageTableSelection(Language.DEFAULT, opened)
                alternative != null -> LanguageTableSelection(alternative, opened)
                else -> LanguageTableSelection(opened)
            }
        }
    }
}

internal fun languageFileCode(path: String): String? =
    path.replace('\\', '/').substringAfterLast('/').lowercase()
        .takeIf { it.endsWith(".json") }?.removeSuffix(".json")?.takeIf(String::isNotEmpty)
