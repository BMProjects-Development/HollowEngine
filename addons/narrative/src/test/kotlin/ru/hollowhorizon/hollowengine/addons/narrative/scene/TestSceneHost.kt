package ru.hollowhorizon.hollowengine.addons.narrative.scene

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.minecraft.server.MinecraftServer
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryAnchor

/**
 * Plays a scene the way a story does, without one: tracks run in [scope] instead of as branches, journaled and
 * temporary commands just run, and the story has no variables of its own.
 */
internal class TestSceneHost(private val scope: CoroutineScope, private val player: ScenePlayer) : SceneHost {
    private val running = LinkedHashMap<String, Job>()

    override val server: MinecraftServer get() = error("Scene tests run no commands")

    override val variables = object : SceneVariables {
        override fun get(name: String): StoryValue? = null

        override fun set(name: String, value: StoryValue) = false
    }

    override fun step() = Unit

    override suspend fun journaled(statement: String, call: suspend () -> Unit) = call()

    override suspend fun temporary(until: String?, call: suspend () -> Unit) = call()

    override fun checkpoint(name: String) = Unit

    override fun rewind(name: String?) = throw StoryRuntimeException("Scene tests have no story to rewind")

    override suspend fun stage(
        script: String,
        stage: String,
        positional: List<StoryValue>,
        named: Map<String, StoryValue>,
    ) = throw StoryRuntimeException("Scene tests have no stages")

    override suspend fun startTrack(name: String, address: String, anchor: StoryAnchor) {
        running.remove(name)?.cancel()
        val job = scope.launch {
            player.playTrack(address, anchor, null, null, step = { _, _ -> }, journaled = { _, call -> call() })
        }
        running[name] = job
        job.invokeOnCompletion { if (running[name] === job) running.remove(name) }
    }

    override fun tracks(): Map<String, SceneTrack> = running.mapValues { (_, job) ->
        object : SceneTrack {
            override suspend fun join() = job.join()

            override fun cancel() = job.cancel()
        }
    }
}

/** A scene of [sources] playing `test:main.story` on a [TestSceneHost] whose tracks run in [scope]. */
internal fun testScene(
    scope: CoroutineScope,
    sources: Map<String, String>,
    library: SceneLibrary = SceneLibrary(),
    locale: String? = null,
): ScenePlayer {
    library.addProvider { address -> sources[address] }
    val player = ScenePlayer("test:main.story", library, locale = locale)
    player.host = TestSceneHost(scope, player)
    return player
}
