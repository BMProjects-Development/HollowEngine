package ru.hollowhorizon.hollowengine.common.scripting.source

import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.HollowEngineBuild
import ru.hollowhorizon.hollowengine.common.addons.HollowAddonClassLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Notified whenever a namespace appears or disappears, so script-backed state can be rebuilt. */
fun interface ScriptSourceListener {
    fun onScriptSourceChanged(namespace: String, available: Boolean)
}

/**
 * Every script the engine can see, grouped by the namespace that owns it. The sandbox directory is
 * registered automatically; addons register and unregister themselves as they are loaded and unloaded.
 */
object ScriptRegistry {
    /** Namespace of the scripts the engine ships with itself. */
    const val ENGINE_NAMESPACE = "hollowengine"

    private val lock = Any()
    private val sources = LinkedHashMap<String, ScriptSource>()
    private val listeners = CopyOnWriteArrayList<ScriptSourceListener>()
    private val filesToIds = ConcurrentHashMap<String, ScriptId>()

    /** Delegating loaders built for namespaces that depend on others; dropped when the sources change. */
    private val dependencyLoaders = HashMap<String, ClassLoader>()

    @Volatile
    private var sandboxSource: SandboxScriptSource = SandboxScriptSource()

    init {
        sources[sandboxSource.namespace] = sandboxSource
        registerEngineScripts()
    }

    val sandbox: SandboxScriptSource get() = sandboxSource

    /** Namespace that unqualified script paths belong to. */
    val sandboxNamespace: String get() = sandboxSource.namespace

    fun sources(): List<ScriptSource> = synchronized(lock) { sources.values.toList() }

    fun source(namespace: String): ScriptSource? = synchronized(lock) { sources[namespace] }

    fun register(source: ScriptSource) {
        synchronized(lock) {
            val existing = sources[source.namespace]
            require(existing == null) {
                "Script namespace '${source.namespace}' is already provided by $existing"
            }
            sources[source.namespace] = source
        }
        HollowEngine.LOGGER.debug("Registered script namespace '{}'", source.namespace)
        notifyListeners(source.namespace, available = true)
    }

    fun unregister(namespace: String) {
        val removed = synchronized(lock) {
            if (namespace == sandboxSource.namespace) null else sources.remove(namespace)
        } ?: return
        filesToIds.values.removeIf { it.namespace == namespace }
        HollowEngine.LOGGER.debug("Unregistered script namespace '{}'", removed.namespace)
        notifyListeners(namespace, available = false)
    }

    fun addListener(listener: ScriptSourceListener) {
        listeners += listener
    }

    fun removeListener(listener: ScriptSourceListener) {
        listeners -= listener
    }

    /**
     * Parses a user- or save-supplied path. Unqualified paths belong to the sandbox and are written
     * relative to the `hollowengine` directory, which is the spelling that existed before namespaces.
     */
    fun parse(raw: String): ScriptId {
        val id = ScriptId.parse(raw, sandboxNamespace)
        if (id.namespace != sandboxNamespace) return id
        return ScriptId(id.namespace, id.path.removePrefix(SandboxScriptSource.SCRIPTS_DIRECTORY + "/"))
    }

    /** Inverse of [parse]. Sandbox scripts keep their old, unqualified spelling. */
    fun display(id: ScriptId): String = if (id.namespace == sandboxNamespace) {
        "${SandboxScriptSource.SCRIPTS_DIRECTORY}/${id.path}"
    } else {
        id.qualified
    }

    /** Every known script, or only those whose file name ends with [extension] when it is given. */
    fun list(extension: String? = null): List<ScriptId> = sources().flatMap { source ->
        runCatching { source.list() }
            .onFailure { HollowEngine.LOGGER.error("Failed to list scripts of '${source.namespace}'", it) }
            .getOrDefault(emptyList())
    }.filter { extension == null || it.path.endsWith(extension) }

    fun listIn(namespace: String, extension: String? = null): List<ScriptId> =
        source(namespace)?.list().orEmpty().filter { extension == null || it.path.endsWith(extension) }

    fun artifacts(id: ScriptId): ScriptArtifacts? = source(id.namespace)?.let { source ->
        runCatching { source.read(id) }
            .onFailure { HollowEngine.LOGGER.error("Failed to read script $id", it) }
            .getOrNull()
            ?.also { artifacts ->
                artifacts.sourceFile?.let { file -> filesToIds[file.canonicalPath] = id }
            }
    }

    fun artifacts(raw: String): ScriptArtifacts? = artifacts(parse(raw))

    /**
     * The script a materialised source file belongs to. Addon sources are extracted into the cache
     * directory, so the path alone does not say which namespace owns them.
     */
    fun idOf(file: File): ScriptId? = filesToIds[file.canonicalPath]

    /**
     * Classpath the scripts of [namespace] compile against: its own, plus that of every namespace it
     * declares in `dependsOn`, so a project can call the classes of the addons it depends on.
     */
    fun classpath(namespace: String): List<File> = closure(namespace)
        .flatMap { source -> source.classpath }
        .distinctBy { file -> file.absoluteFile.normalize() }

    /**
     * Classloader the scripts of [namespace] run in: the one of the namespace itself, delegating to the
     * loaders of the namespaces it depends on.
     */
    fun classLoader(namespace: String): ClassLoader? {
        val source = source(namespace) ?: return null
        val dependencies = closure(namespace).filter { it !== source }.map(ScriptSource::classLoader).distinct()
        if (dependencies.isEmpty()) return source.classLoader
        return synchronized(lock) {
            dependencyLoaders.getOrPut(namespace) {
                HollowAddonClassLoader(emptyArray(), source.classLoader, dependencies)
            }
        }
    }

    /** [namespace] and everything it depends on, breadth first and without revisiting a namespace. */
    private fun closure(namespace: String): List<ScriptSource> {
        val visited = LinkedHashMap<String, ScriptSource>()
        fun visit(id: String) {
            if (id in visited) return
            val source = source(id) ?: return
            visited[id] = source
            source.dependencies.forEach(::visit)
        }
        visit(namespace)
        return visited.values.toList()
    }

    /**
     * Whether a script of [from] may pull in a script of [to] through `@file:Import`. A namespace can
     * always import from itself; anything else has to be declared as a dependency.
     */
    fun canImport(from: String, to: String): Boolean {
        if (from == to) return true
        return source(from)?.dependencies?.contains(to) == true
    }

    /** Re-reads `hollowengine/META-INF/plugin.properties`. Only useful for tests and tooling. */
    fun reloadSandbox(source: SandboxScriptSource = SandboxScriptSource()) {
        val previous = synchronized(lock) {
            val previous = sandboxSource
            sources.remove(previous.namespace)
            sandboxSource = source
            sources[source.namespace] = source
            previous
        }
        if (previous.namespace != source.namespace) {
            notifyListeners(previous.namespace, available = false)
        }
        notifyListeners(source.namespace, available = true)
    }

    /** The engine's own scripts, when this build shipped any. */
    private fun registerEngineScripts() {
        val engine = runCatching {
            ClasspathScriptSource(
                namespace = ENGINE_NAMESPACE,
                classLoader = HollowEngine::class.java.classLoader,
                fingerprint = HollowEngineBuild.VERSION,
            )
        }.onFailure { HollowEngine.LOGGER.error("Failed to read the engine's own scripts", it) }.getOrNull()
        if (engine == null || engine.isEmpty()) return
        sources[engine.namespace] = engine
    }

    private fun notifyListeners(namespace: String, available: Boolean) {
        synchronized(lock) { dependencyLoaders.clear() }
        listeners.forEach { listener ->
            runCatching { listener.onScriptSourceChanged(namespace, available) }
                .onFailure { HollowEngine.LOGGER.error("Script source listener failed for '$namespace'", it) }
        }
    }
}
