package ru.hollowhorizon.hollowengine.client.utils

import net.minecraft.locale.Language

val String.lang: String get() = Language.getInstance().getOrDefault(this)

fun String.lang(vararg args: Any) = Language.getInstance().getOrDefault(this).format(*args)

fun String.langOrDefault(default: String, vararg args: Any): String {
    val value = Language.getInstance().getOrDefault(this, default)
    return if (args.isEmpty()) value else value.format(*args)
}
