package ru.hollowhorizon.hollowengine.common.addons

import java.net.URL
import java.net.URLClassLoader

internal class HollowAddonClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    private val dependencies: List<ClassLoader>,
) : URLClassLoader(urls, parent) {
    private val parentFirstPackages = listOf(
        "ru.hollowhorizon.hollowengine.Hollow",
        "ru.hollowhorizon.hollowengine.common.addons.",
        "ru.hollowhorizon.hollowengine.common.files.",
        "ru.hollowhorizon.hollowengine.common.network.",
        "ru.hollowhorizon.hollowengine.common.scripting.",
        "ru.hollowhorizon.hollowengine.common.utils.",
        "ru.hollowhorizon.hollowengine.client.gui.scripting.files.text.util.",
        "kotlin.annotation.",
        "kotlin.collections.",
        "kotlin.comparisons.",
        "kotlin.concurrent.",
        "kotlin.contracts.",
        "kotlin.coroutines.",
        "kotlinx.coroutines.",
        "kotlin.enums.",
        "kotlin.experimental.",
        "kotlin.internal.",
        "kotlin.io.",
        "kotlin.jvm.",
        "kotlin.math.",
        "kotlin.properties.",
        "kotlin.random.",
        "kotlin.ranges.",
        "kotlin.sequences.",
        "kotlin.script.experimental.api.",
        "kotlin.script.experimental.host.",
        "kotlin.script.experimental.util.",
        "kotlin.text.",
        "kotlin.time.",
        "net.minecraft.",
        "com.mojang.",
        "org.slf4j.",
        "org.apache.logging.",
    )

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        findLoadedClass(name)?.let { return@synchronized it }
        var isParentFirst = parentFirstPackages.any(name::startsWith)

        if (name.startsWith("kotlin.reflect.")) {
            val reflectClassName = name.removePrefix("kotlin.reflect.")
            if(!reflectClassName.contains('.')) isParentFirst = true
        }

        if (isParentFirst) {
            try {
                return@synchronized parent.loadClass(name)
            } catch (_: ClassNotFoundException) {
                // Addon-owned classes may share the engine's package prefix.
            }
        }

        dependencies.forEach { dependency ->
            try {
                return@synchronized dependency.loadClass(name)
            } catch (_: ClassNotFoundException) {
                // Continue through the dependency chain.
            }
        }
        val loaded = try {
            findClass(name).also { loaded ->
                if (resolve) resolveClass(loaded)
            }
        } catch (_: ClassNotFoundException) {
            super.loadClass(name, resolve)
        }
        loaded
    }
}
