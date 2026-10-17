package ru.hollowhorizon.hollowengine.common.ide.session

import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryKeyDescriptor

/** Standalone PSI does not load plugin descriptors, which supply Java's inference defaults. */
internal fun registerJavaAnalysisRegistryKeys() {
    val descriptorPath = "intellij.java.psi.impl.xml"
    val stream = requireNotNull(JavaLanguageAnalyzer::class.java.classLoader.getResourceAsStream(descriptorPath)) {
        "Java PSI module descriptor is missing: $descriptorPath"
    }
    val descriptor = stream.use { JDOMUtil.load(it) }
    val keys = descriptor.getChildren("extensions").flatMap { it.getChildren("registryKey") }.associate { key ->
        val name = requireNotNull(key.getAttributeValue("key"))
        name to RegistryKeyDescriptor(
            name,
            key.getAttributeValue("description").orEmpty(),
            requireNotNull(key.getAttributeValue("defaultValue")),
            key.getAttributeValue("restartRequired").toBoolean(),
            key.getAttributeValue("overrides").toBoolean(),
            descriptor.getChildText("id"),
            descriptorPath,
        )
    }
    Registry.mutateContributedKeys { current -> keys + current }
}
