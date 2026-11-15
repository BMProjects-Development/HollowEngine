package ru.hollowhorizon.hollowengine.client.ui.ide.remote

import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.CompletableDeferred
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.common.addons.project.remote.RemoteProjectError
import ru.hollowhorizon.hollowengine.common.addons.project.remote.RemoteProjectFile
import ru.hollowhorizon.hollowengine.common.addons.project.remote.projectPathContains
import ru.hollowhorizon.hollowengine.common.scripting.ide.AnalysisSourcePending
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptAnalysisProject
import ru.hollowhorizon.hollowengine.common.scripting.ide.ScriptingAnalyzer
import ru.hollowhorizon.hollowengine.common.scripting.ide.withAnalysisSources
import java.io.IOException
import java.util.WeakHashMap

/** Source cache for one grant of access to a server project. Packet operations run on the client thread. */
internal class RemoteProjectAnalysis(val session: Long, private val onHintsChanged: () -> Unit) : ScriptAnalysisProject {
    private sealed interface Source {
        class Pending(val hint: Boolean, val ready: CompletableDeferred<Unit> = CompletableDeferred()) : Source
        class Ready(path: String, val file: RemoteProjectFile?, val error: RemoteProjectError?) : Source {
            val text by lazy { file?.text(path) }
        }
    }

    private val sources = LinkedHashMap<String, Source>()
    private val analyzers = WeakHashMap<ScriptingAnalyzer, ScriptingAnalyzer>()
    private var closed = false
    private val version = mutableLongStateOf(0L)
    val observableRevision: Long get() = version.longValue
    @Volatile
    override var revision = 0L
        private set

    fun bind(analyzer: ScriptingAnalyzer): ScriptingAnalyzer = synchronized(analyzers) {
        analyzers[analyzer] ?: analyzer.forProject(this).also { scoped ->
            if (scoped !== analyzer) analyzers[analyzer] = scoped
        }
    }

    override fun read(path: String): String? = readSource(path, hint = false)?.text

    override fun readForHint(path: String): String? = readSource(path, hint = true)?.text

    suspend fun readBytes(path: String): ByteArray? = withAnalysisSources {
        readSource(path, hint = false)?.file?.bytes?.copyOf()
    }

    private fun readSource(path: String, hint: Boolean): Source.Ready? {
        var start: Source.Pending? = null
        val source = synchronized(sources) {
            if (closed) throw IOException(RemoteProjectError.DISCONNECTED.message())
            sources.getOrPut(path) { Source.Pending(hint).also { start = it } }
        }
        start?.let { pending -> Minecraft.getInstance().execute { fetch(path, pending) } }
        return when (source) {
            is Source.Pending -> if (hint) null else throw AnalysisSourcePending(source.ready)
            is Source.Ready -> {
                source.error?.let { throw IOException("$path: ${it.message()}") }
                source
            }
        }
    }

    private fun fetch(path: String, pending: Source.Pending) {
        if (synchronized(sources) { sources[path] !== pending }) return
        if (session != RemoteProjectConnection.session) {
            close()
            return
        }
        RemoteProjectConnection.read(path) { file, error ->
            val failure = when {
                error == RemoteProjectError.MISSING || error == RemoteProjectError.INVALID_PATH -> null
                else -> error
            }
            val refreshHints = synchronized(sources) {
                if (sources[path] !== pending) return@synchronized false
                sources[path] = Source.Ready(path, file, failure)
                if (pending.hint && file != null) {
                    changed()
                    true
                } else false
            }
            pending.ready.complete(Unit)
            if (refreshHints) onHintsChanged()
        }
    }

    fun invalidate(vararg paths: String) = synchronized(sources) {
        val pending = mutableListOf<Source.Pending>()
        val entries = sources.entries.iterator()
        while (entries.hasNext()) {
            val (path, source) = entries.next()
            if (paths.isEmpty() || paths.any { projectPathContains(it, path) }) {
                entries.remove()
                if (source is Source.Pending) pending += source
            }
        }
        changed()
        pending.forEach { it.ready.complete(Unit) }
    }

    fun close() = synchronized(sources) {
        closed = true
        invalidate()
    }

    private fun changed() {
        revision++
        version.longValue = revision
    }
}
