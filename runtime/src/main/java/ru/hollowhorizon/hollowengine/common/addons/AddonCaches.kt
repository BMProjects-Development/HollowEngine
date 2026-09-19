package ru.hollowhorizon.hollowengine.common.addons

import ru.hollowhorizon.hollowengine.HollowEngineBuild
import ru.hollowhorizon.hollowengine.common.files.CacheCleanup
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager
import ru.hollowhorizon.hollowengine.common.scripting.cache.ScriptCache
import ru.hollowhorizon.hollowengine.common.scripting.source.ScriptRegistry

/**
 * What the addons found at launch still need from the caches built out of addon jars.
 */
internal object AddonCaches {
    /** Right after staging: copies, unpacked classes and libraries, and scripts extracted from jars. */
    fun retainStaged(store: HollowAddonArtifactStore, candidates: List<HollowAddonCandidate>) {
        val fingerprints = candidates.mapTo(HashSet()) { it.fingerprint }
        store.retain(fingerprints)
        val keys = fingerprints + HollowEngineBuild.VERSION
        CacheCleanup.retainNested(DirectoryManager.SCRIPT_SOURCE_CACHE, keys)
        CacheCleanup.retainNested(DirectoryManager.SCRIPT_BUNDLE_CACHE, keys)
    }

    /** Once the addons registered their scripts: compiled artifacts of scripts and namespaces that are gone. */
    fun retainCompiledScripts(candidates: List<HollowAddonCandidate>) {
        val namespaces = ScriptRegistry.sources().mapTo(HashSet()) { it.namespace }
        candidates.mapTo(namespaces) { it.descriptor.id }
        ScriptCache.retainNamespaces(namespaces)
        ScriptCache.prune(ScriptRegistry.list())
    }
}
