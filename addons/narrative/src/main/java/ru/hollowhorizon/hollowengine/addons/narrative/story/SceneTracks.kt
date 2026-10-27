package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.CompletableDeferred
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.addons.narrative.scene.ScenePlayer
import ru.hollowhorizon.hollowengine.addons.narrative.scene.SceneTrack
import ru.hollowhorizon.hollowengine.addons.narrative.scene.lang.StoryAnchor

/**
 * The `@async` tracks of scenes, each a branch of the story the scene plays in: it keeps its place in the track
 * across restarts, the journal records its actions under the branch, and it ends with the scene.
 */
internal object SceneTracks {
    const val KEY = "hollowengine-narrative:scene-track"
    private const val STAGE = "track"

    private val STRING = storySerializer<String>()
    private val LABEL = storySerializer<String?>()
    private val INT = storySerializer<Int>()

    /** The players of the scenes playing, by the path of the run whose top frame each is; tracks wait for them. */
    private val scenes = HashMap<String, CompletableDeferred<ScenePlayer>>()

    fun attach(run: StoryRun, scene: ScenePlayer) {
        scenes.getOrPut(run.path) { CompletableDeferred() }.complete(scene)
    }

    fun detach(run: StoryRun) {
        scenes.remove(run.path)
    }

    /** Starts the track [name] of the scene in [frame], stopping a track so named first. */
    suspend fun start(frame: StoryFrame, name: String, address: String, anchor: StoryAnchor) {
        frame.run.children[name]?.stop()
        val args = CompoundTag()
        StoryValues.put(args, "name", STRING, name)
        StoryValues.put(args, "address", STRING, address)
        StoryValues.put(args, "label", LABEL, anchor.label)
        StoryValues.put(args, "offset", INT, anchor.offset)
        StoryValues.put(args, "line", INT, anchor.line)
        frame.run.branch(name, StageLambda0<Unit>(KEY, STAGE, args))
    }

    /** The tracks the scene in [frame] started that are still there. */
    fun of(frame: StoryFrame): Map<String, SceneTrack> =
        frame.children.mapNotNull { name -> frame.run.children[name]?.let { name to Track(it) } }.toMap()

    private class Track(private val run: StoryRun) : SceneTrack {
        override suspend fun join() {
            run.awaitDone()
        }

        override fun cancel() = run.stop()
    }

    @StageEntry(STAGE)
    suspend fun entry(args: CompoundTag): Any {
        play(
            StoryValues.get(args, "name", STRING) as String,
            StoryValues.get(args, "address", STRING) as String,
            StoryAnchor(
                StoryValues.get(args, "label", LABEL) as String?,
                StoryValues.get(args, "offset", INT) as Int,
                StoryValues.get(args, "line", INT) as Int,
            ),
        )
        return Unit
    }

    private suspend fun play(name: String, address: String, anchor: StoryAnchor) {
        val frame = StoryFrames.enter(this, STAGE, 0, arrayOf(SceneStage.STATEMENT))
        try {
            frame.saveArg("name", STRING, name)
            frame.saveArg("address", STRING, address)
            frame.saveArg("label", LABEL, anchor.label)
            frame.saveArg("offset", INT, anchor.offset)
            frame.saveArg("line", INT, anchor.line)
            val scene = frame.run.parent ?: throw StoryException("Track '$name' runs only as a branch of a scene")
            val player = scenes.getOrPut(scene.path) { CompletableDeferred() }.await()
            val saved = frame.locals.takeIf { it.contains(HASH) }?.let {
                StoryAnchor(
                    it.getString(LABEL_KEY).takeIf { _ -> it.contains(LABEL_KEY) },
                    it.getInt(OFFSET),
                    it.getInt(LINE)
                )
            }
            player.playTrack(
                address,
                anchor,
                saved,
                frame.locals.getString(HASH),
                step = { hash, at -> step(frame, hash, at) },
                journaled = { statement, call -> SceneStage.journaled(frame, statement, call) },
            )
        } catch (failure: Throwable) {
            frame.abort(failure)
            throw failure
        } finally {
            frame.leave()
        }
    }

    /** Remembers that the track is at [anchor] of the text [hash]; a restart repeats that statement. */
    private fun step(frame: StoryFrame, hash: String, anchor: StoryAnchor) {
        frame.locals.putString(HASH, hash)
        anchor.label?.let { frame.locals.putString(LABEL_KEY, it) } ?: frame.locals.remove(LABEL_KEY)
        frame.locals.putInt(OFFSET, anchor.offset)
        frame.locals.putInt(LINE, anchor.line)
        frame.standAt(SceneStage.STATEMENT, calling = false)
        frame.segmentMark = frame.run.journal.next
    }

    private const val HASH = "hash"
    private const val LABEL_KEY = "label"
    private const val OFFSET = "offset"
    private const val LINE = "line"
}
