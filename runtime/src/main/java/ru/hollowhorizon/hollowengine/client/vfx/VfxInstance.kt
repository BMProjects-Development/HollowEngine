package ru.hollowhorizon.hollowengine.client.vfx

import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.client.vfx.render.VfxDrawList
import ru.hollowhorizon.hollowengine.common.data.NbtDataStore
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.playback.PlaybackSections
import ru.hollowhorizon.hollowengine.common.vfx.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.random.Random

/**
 * One playing effect.
 *
 * Time comes from the caller rather than from a clock of its own, because the same instance plays in
 * the world off the game clock and in the editor off a scrubbing timeline.
 */
class VfxInstance(
    val effect: VfxEffect,
    val asset: String,
    val environment: VfxEnvironment = VfxEnvironment.EMPTY,
    seed: Int = Random.nextInt(),
) {
    private val expressions = VfxExpressions.compile(effect.expressions(), asset)

    /** Which properties of which node have a track, and how many channels each carries. */
    private val driven: Map<String, Map<VfxProperty, Int>> =
        effect.timeline.tracks.groupBy { it.node }.mapValues { (nodeId, tracks) ->
            val node = effect.node(nodeId)
            tracks.mapNotNull { track ->
                if (node == null || track.curves.none { it.keys.isNotEmpty() }) return@mapNotNull null
                val animatable = VfxAnimatables.of(node, track.property) ?: return@mapNotNull null
                track.property to animatable.kind.channels
            }.toMap()
        }

    internal fun drivenProperties(nodeId: String): Map<VfxProperty, Int> = driven[nodeId].orEmpty()

    /** The frame the whole effect sits in; a bone or a moving entity writes into it. */
    val root = VfxFrame()

    /** Where effect space sits in the world. */
    var origin: Vec3 = Vec3.ZERO
        private set

    /** Where world-space particles are measured from; fixed, so they do not follow a moving origin. */
    var anchor: Vec3 = Vec3.ZERO
        private set

    /** [origin] minus [anchor], the only part of the two a float can safely hold. */
    val originMinusAnchor = MutableVec3f()

    /** Where the camera is, in effect space; whoever plays the effect keeps it current. */
    val camera = MutableVec3f()

    val nodes: List<VfxNodeRuntime> = effect.nodes.mapIndexed { index, node ->
        VfxNodeRuntime(node, null, this, expressions, seed + index * 7919, drivenProperties(node.id))
    }

    /** Every node, parents before children, which is also the order they update and draw in. */
    val allNodes: List<VfxNodeRuntime> = nodes.flatMap { it.walk() }

    private val behaviors: List<VfxNodeBehavior> = allNodes.mapNotNull { it.behavior }

    val emitters: List<VfxEmitter> = behaviors.filterIsInstance<VfxEmitter>()

    private val timeline = VfxTimelinePlayer(effect.timeline, allNodes)

    /** Seconds since the effect started playing. */
    var time: Float = 0f
        private set

    /**
     * Where the timeline is: it runs with the clock, starts over when it loops, and goes where the rules of its
     * sections send it.
     */
    var timelineTime: Float = 0f
        private set

    /** Whether the timeline has just arrived where it is, so a section starting there checks its rules. */
    private var entering = true

    /** Whether a rule or a jump moved the timeline since the start, so it no longer runs with the clock. */
    private var detoured = false

    private val conditionContext = VfxEvalContext()
    private val compiledConditions = HashMap<String, (VfxEvalContext) -> Float>()

    /** How fast the origin is moving, for particles that inherit it. */
    var velocityX: Float = 0f
        private set
    var velocityY: Float = 0f
        private set
    var velocityZ: Float = 0f
        private set

    var partialTick: Float = 0f
    var gameTime: Float = 0f

    /** The data store of the handle, flattened to the numbers expressions read as `d.`. */
    var data: Map<String, Float> = emptyMap()
        private set

    /** Whether anything here is lit by the world, so light is only looked up when it is used. */
    val readsLight: Boolean =
        effect.walk().filterIsInstance<VfxSurfaceSpec>().any { it.material.lighting == VfxLighting.WORLD } ||
                effect.walk().any { it is VfxModelSpec && !it.emissive }

    var isStopping: Boolean = false
        private set

    /**
     * Whether everything has stopped and nothing is left on screen. Without emitters, an effect
     * ends with a timeline that does not loop.
     */
    val isFinished: Boolean
        get() = when {
            isStopping -> behaviors.all { it.isIdle }
            emitters.isNotEmpty() -> emitters.all { it.isFinished }
            else -> !effect.timeline.loop && timelineTime >= effect.timeline.duration
        }

    /** How many particles this effect may still create this frame; the scene refills it. */
    var budget: Int = Int.MAX_VALUE

    val particleCount: Int get() = emitters.sumOf { it.particles.count }

    init {
        moveTo(Vec3.ZERO, resetAnchor = true)
    }

    fun readData(store: NbtDataStore) {
        data = store.numericPaths()
    }

    /** Takes [values] as the data expressions read as `d.`, by path, as a preview does that has no handle. */
    fun useData(values: Map<String, Float>) {
        data = values
    }

    /** Puts effect space somewhere in the world. The anchor only moves when asked. */
    fun moveTo(position: Vec3, resetAnchor: Boolean = false) {
        if (resetAnchor) anchor = position
        origin = position
        originMinusAnchor.set(
            (origin.x - anchor.x).toFloat(),
            (origin.y - anchor.y).toFloat(),
            (origin.z - anchor.z).toFloat(),
        )
    }

    /** Remembers where the camera is in the world, for distances and camera-facing ribbons. */
    fun setCamera(world: Vec3) {
        camera.set((world.x - origin.x).toFloat(), (world.y - origin.y).toFloat(), (world.z - origin.z).toFloat())
    }

    /** Remembers how fast the origin is moving, for inherited velocity. */
    fun setVelocity(x: Float, y: Float, z: Float) {
        velocityX = x
        velocityY = y
        velocityZ = z
    }

    fun stop(immediate: Boolean) {
        isStopping = true
        behaviors.forEach { it.stop(immediate) }
    }

    /**
     * Whether the effect is held: its clock stands and its emitters make nothing new, while what they made lives
     * out its lifetime, and what the effect draws once rather than on particles is gone until it goes on.
     */
    var isPaused: Boolean = false
        private set

    fun pause() {
        if (isPaused) return
        isPaused = true
        emitters.forEach(VfxEmitter::hold)
    }

    /** Goes on from where it was held: the emitters make particles again. */
    fun resume() {
        if (!isPaused) return
        isPaused = false
        emitters.forEach(VfxEmitter::release)
    }

    /** Lets the effect go on when [emitting], holds it otherwise. */
    fun emit(emitting: Boolean) = if (emitting) resume() else pause()

    /** Advances everything by [dt] seconds and leaves the nodes ready to draw. */
    fun update(dt: Float) {
        if (dt > 0f && !isPaused) {
            time += dt
            advanceTimeline(dt)
        }
        pose()
        behaviors.forEach { it.update(dt) }
    }

    /** Whether [seek] is playing the effect through, which takes the timeline straight along with the clock. */
    private var seeking = false

    /** Moves the timeline on by [dt] seconds, through its sections unless [seeking], starting over when it loops. */
    private fun advanceTimeline(dt: Float) {
        val spec = effect.timeline
        val duration = spec.duration
        val next = timelineTime + dt
        val straight = if (spec.loop && duration > 0f) next.mod(duration) else next
        if (seeking || !spec.hasRules) {
            timelineTime = straight
            entering = false
            return
        }
        var at = PlaybackSections.advance(spec.sections, spec.events, timelineTime, dt, entering, ::holds).time
        if (spec.loop && duration > 0f && at >= duration) {
            at = PlaybackSections.advance(spec.sections, spec.events, 0f, (at - duration).mod(duration), true, ::holds).time
        }
        if (abs(at - straight) > PlaybackSections.EPSILON) detoured = true
        entering = false
        timelineTime = at
    }

    /** Whether the condition [source] of a section holds now. */
    private fun holds(source: String): Boolean {
        val compiled = compiledConditions.getOrPut(source) { expressions.float(source) }
        conditionContext.age = timelineTime
        conditionContext.effectTime = time
        conditionContext.emitterAge = time
        conditionContext.gameTime = gameTime
        conditionContext.partialTick = partialTick
        conditionContext.data = data
        return compiled(conditionContext) != 0f
    }

    /** Moves the timeline to the event [name] and plays on from there; false when the effect has no such event. */
    fun jumpTo(name: String): Boolean {
        val target = effect.timeline.events.firstOrNull { it.name == name }?.time ?: return false
        timelineTime = target
        entering = true
        detoured = true
        pose()
        return true
    }

    /**
     * What an editor holds over the tracks, by node and property: an edit shown until the playhead moves, written
     * over what the timeline says. Null where nothing is held.
     */
    var held: (nodeId: String, property: VfxProperty) -> FloatArray? = { _, _ -> null }

    /** Places the nodes again where they are now, for an editor that changed what it holds. */
    fun repose() = pose()

    /** Places every node for the current time: the authored transform, then the timeline over it. */
    private fun pose() {
        nodes.forEach { it.reset() }
        if (!timeline.isEmpty) timeline.apply(timelineTime, held)
        nodes.forEach { it.place(root) }
        allNodes.forEach { it.fillContext() }
    }

    /** Adds what every active node draws; [placement] maps effect space to the space of the view. */
    fun collect(into: VfxDrawList, placement: Matrix4f) {
        allNodes.forEach { node ->
            node.behavior?.frame()
            if (isPaused && node.parent?.behavior !is VfxEmitter) return@forEach
            node.behavior?.collect(into, placement)
        }
    }

    fun node(id: String): VfxNodeRuntime? = allNodes.firstOrNull { it.spec.id == id }

    /**
     * Takes the placement of [next] - where nodes are, whether they are on, what they are called -
     * without building the effect again. Returns false when [next] differs in anything else.
     */
    fun adoptPlacement(next: VfxEffect): Boolean {
        if (next.withoutPlacement() != effect.withoutPlacement()) return false
        allNodes.forEach { node ->
            val spec = next.node(node.spec.id) ?: return@forEach
            node.authoredTransform = spec.transform
            node.authoredEnabled = spec.enabled
        }
        pose()
        return true
    }

    /**
     * Back to the first moment. No particles, every emitter about to start its first loop, and
     * nodes where timeline has them at zero.
     */
    fun restart() {
        time = 0f
        timelineTime = 0f
        entering = true
        detoured = false
        isStopping = false
        isPaused = false
        behaviors.forEach { it.restart() }
        pose()
    }

    /**
     * Takes the effect from [from] to [to] seconds of a clock it does not keep, such as a timeline's playhead. Going
     * on, it plays the way there in steps short enough for particles to move as they do frame by frame, at most
     * [maxSteps] of them; going back, it starts over and plays up to [to], as a simulation does not run backwards.
     */
    fun seek(
        from: Float,
        to: Float,
        maxSteps: Int = MAX_SEEK_STEPS,
        emittingAt: (Float) -> Boolean = { true },
        advance: (Float) -> Unit = ::update,
    ) {
        val target = to.coerceAtLeast(0f)
        var at = from
        if (target < at || detoured) {
            restart()
            at = 0f
        }
        seeking = true
        try {
            playTo(at, target, maxSteps, emittingAt, advance)
        } finally {
            seeking = false
        }
    }

    private fun playTo(at: Float, target: Float, maxSteps: Int, emittingAt: (Float) -> Boolean, advance: (Float) -> Unit) {
        val distance = target - at
        if (distance <= SEEK_EPSILON) {
            emit(emittingAt(target))
            advance(0f)
            return
        }
        val steps = ceil(distance / SEEK_STEP).toInt().coerceIn(1, maxSteps)
        val step = distance / steps
        for (index in 1..steps) {
            emit(emittingAt(at + step * index))
            advance(step)
        }
    }

    /** Whether one more particle fits in the global budget. */
    fun requestParticle(): Boolean {
        if (budget <= 0) return false
        budget--
        return true
    }

    /** The packed light at a particle, or full bright when nothing here is lit by the world. */
    fun lightAt(emitter: VfxEmitter, slot: Int): Int {
        if (!readsLight) return FULL_BRIGHT
        val point = emitter.toEffectSpace(slot, lightCursor)
        return environment.lightAt(origin.x + point.x, origin.y + point.y, origin.z + point.z)
    }

    /** The packed light at a point of effect space. */
    fun lightAt(point: Vec3f): Int {
        if (!readsLight) return FULL_BRIGHT
        return environment.lightAt(origin.x + point.x, origin.y + point.y, origin.z + point.z)
    }

    private val lightCursor = MutableVec3f()

    companion object {
        const val FULL_BRIGHT = 0xF000F0

        /** How finely [seek] plays an effect through, and how many steps it takes at most unless told otherwise. */
        const val SEEK_STEP = 1f / 30f
        const val MAX_SEEK_STEPS = 600
        private const val SEEK_EPSILON = 1.0e-4f
    }
}

/**
 * Plays the timeline of an effect onto its live nodes.
 *
 * Curves are sampled the same way the curve editor draws them.
 */
class VfxTimelinePlayer(private val spec: VfxTimelineSpec, nodes: List<VfxNodeRuntime>) {
    /** Curves paired with the channel they drive, resolved once. */
    private class PreparedTrack(
        val node: VfxNodeRuntime,
        val property: VfxProperty,
        val channels: Int,
        val defaults: FloatArray,
        val curves: List<Pair<Int, VfxCurve>>,
        val drive: VfxDrive?,
    )

    private val tracks: List<PreparedTrack>
    private val values = FloatArray(4)

    val isEmpty: Boolean get() = tracks.isEmpty()

    init {
        val byId = nodes.associateBy { it.spec.id }
        tracks = spec.tracks.mapNotNull { track -> prepare(track, byId) }
    }

    /** Writes the tracks at [time] of the timeline onto their nodes, with what [held] has over them. */
    fun apply(time: Float, held: (String, VfxProperty) -> FloatArray? = { _, _ -> null }) {
        tracks.forEach { track -> apply(track, time, held(track.node.spec.id, track.property)) }
    }

    private fun prepare(track: VfxTrack, byId: Map<String, VfxNodeRuntime>): PreparedTrack? {
        val node = byId[track.node] ?: return null
        val property = VfxAnimatables.of(node.spec, track.property) ?: return null
        val channels = property.kind.channels

        val curves = track.curves.filter { it.visible && it.channel in 0 until channels && it.keys.isNotEmpty() }
            .map { it.channel to VfxCurve(it.keys) }
        if (curves.isEmpty()) return null

        val drive = node.drive(track.property)
        drive?.let { target -> curves.forEach { (channel, _) -> target.active[channel] = true } }

        return PreparedTrack(node, track.property, channels, property.read(), curves, drive)
    }

    private fun apply(track: PreparedTrack, time: Float, held: FloatArray?) {
        for (channel in 0 until track.channels) {
            values[channel] = track.defaults.getOrElse(channel) { 0f }
        }

        val placement = when (track.property) {
            VfxProperty.POSITION -> track.node.transform.position
            VfxProperty.ROTATION -> track.node.transform.rotation
            VfxProperty.SCALE -> track.node.transform.scale
            else -> null
        }
        placement?.let {
            values[0] = it.x
            values[1] = it.y
            values[2] = it.z
        }
        track.curves.forEach { (channel, curve) ->
            values[channel] = curve.valueAt(time, values[channel])
        }
        held?.let { for (channel in 0 until track.channels) values[channel] = it.getOrElse(channel) { values[channel] } }

        when (track.property) {
            VfxProperty.ENABLED -> track.node.enabled = values[0] >= 0.5f
            VfxProperty.POSITION -> track.node.transform =
                track.node.transform.copy(position = Vec3f(values[0], values[1], values[2]))

            VfxProperty.ROTATION -> track.node.transform =
                track.node.transform.copy(rotation = Vec3f(values[0], values[1], values[2]))

            VfxProperty.SCALE -> track.node.transform =
                track.node.transform.copy(scale = Vec3f(values[0], values[1], values[2]))

            else -> track.drive?.let { drive ->
                for (channel in 0 until track.channels) drive.values[channel] = values[channel]
            }
        }
    }
}
