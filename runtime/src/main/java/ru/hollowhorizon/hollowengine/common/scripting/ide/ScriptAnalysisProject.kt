package ru.hollowhorizon.hollowengine.common.scripting.ide

import kotlinx.coroutines.Deferred

/** The source files visible to an isolated IDE analysis session. Paths are relative to the project root. */
interface ScriptAnalysisProject {
    val revision: Long

    /**
     * Returns null for a missing file. An asynchronous source can start loading and throw
     * [AnalysisSourcePending]. The caller retries the analysis after the result arrives.
     */
    fun read(path: String): String?

    /** Cached source for decorations that can appear after the initial analysis has been published. */
    fun readForHint(path: String): String? = read(path)
}

class AnalysisSourcePending(internal val ready: Deferred<Unit>) : RuntimeException(null, null, false, false)

/** Wait outside the analyzer's lock, so loading one project never holds up another. */
internal suspend fun <T> withAnalysisSources(analyze: () -> T): T {
    while (true) {
        try {
            return analyze()
        } catch (pending: AnalysisSourcePending) {
            pending.ready.await()
        }
    }
}
