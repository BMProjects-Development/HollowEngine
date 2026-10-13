package ru.hollowhorizon.hollowengine.client.vfx

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.common.data.NbtDataStore
import ru.hollowhorizon.hollowengine.common.vfx.VfxPlayPacket

/**
 * A playing effect and whatever it is following.
 */
class VfxPlayback(
    val handle: Int,
    val instance: VfxInstance,
    private val entity: Entity?,
) {
    private val data = NbtDataStore()
    /** Where it was last frame; NaN until the first one, which has no motion to speak of. */
    private var lastX = Double.NaN
    private var lastY = Double.NaN
    private var lastZ = Double.NaN

    var position: Vec3 = Vec3.ZERO
        private set

    val isFinished: Boolean
        get() = instance.isFinished || (entity != null && !entity.isAlive)

    fun moveTo(next: Vec3) {
        position = next
    }

    fun applyData(tag: CompoundTag) {
        data.load(tag)
        instance.readData(data)
    }

    fun stop(immediate: Boolean) = instance.stop(immediate)

    /** Follows whatever it is bound to, then advances the simulation. */
    fun update(dt: Float, partialTick: Float, gameTime: Float, camera: Vec3) {
        val next = entity?.getPosition(partialTick)?.add(0.0, entity.bbHeight * 0.5, 0.0) ?: position
        if (dt > 0f && !lastX.isNaN()) {
            instance.setVelocity(
                ((next.x - lastX) / dt).toFloat(),
                ((next.y - lastY) / dt).toFloat(),
                ((next.z - lastZ) / dt).toFloat(),
            )
        }
        lastX = next.x
        lastY = next.y
        lastZ = next.z

        position = next
        instance.moveTo(next)
        instance.setCamera(camera)
        instance.partialTick = partialTick
        instance.gameTime = gameTime
        instance.update(dt)
    }
}

/**
 * The effects playing in one level.
 */
class VfxScene(private val environment: VfxEnvironment) {
    private val playbacks = ArrayList<VfxPlayback>()
    private var lastGameTime = Float.NaN

    val isEmpty: Boolean get() = playbacks.isEmpty()

    fun play(handle: Int, asset: String, position: Vec3, entity: Entity?, data: CompoundTag): VfxPlayback? {
        val effect = VfxAssets[asset] ?: return null

        val instance = VfxInstance(effect, asset, environment)
        instance.moveTo(position, resetAnchor = true)

        val playback = VfxPlayback(handle, instance, entity)
        playback.moveTo(position)
        if (!data.isEmpty()) playback.applyData(data)

        playbacks.removeAll { it.handle == handle }
        playbacks += playback
        return playback
    }

    fun of(handle: Int): VfxPlayback? = playbacks.firstOrNull { it.handle == handle }

    fun clear() = playbacks.clear()

    /** Advances everything with the time the game is keeping, seen from [camera]. */
    fun update(camera: Vec3) {
        val now = TickHandler.gameTime
        val dt = when {
            lastGameTime.isNaN() -> 0f
            else -> ((now - lastGameTime) / TICKS_PER_SECOND).coerceIn(0f, MAX_STEP_SECONDS)
        }
        lastGameTime = now

        val budget = VfxBudget.share(playbacks.size)
        val partialTick = TickHandler.partialTick
        playbacks.forEach { playback ->
            playback.instance.budget = budget
            playback.update(dt, partialTick, now, camera)
        }
        playbacks.removeAll { it.isFinished }
    }

    fun forEachInstance(action: (VfxInstance, Vec3) -> Unit) {
        playbacks.forEach { action(it.instance, it.position) }
    }

    private companion object {
        const val TICKS_PER_SECOND = 20f

        /** After a freeze the clock can jump; a particle should not travel half a second in one step. */
        const val MAX_STEP_SECONDS = 0.25f
    }
}

/**
 * How many particles all the effects on screen may have between them.
 */
object VfxBudget {
    var limit: Int = 8000

    fun share(consumers: Int): Int = if (consumers <= 0) limit else (limit / consumers).coerceAtLeast(16)
}

/**
 * The effects of the client, one scene per level.
 */
object VfxScenes {
    private var level: ClientLevel? = null
    private var scene: VfxScene? = null

    /** The scene of the level the player is in, created on first use. */
    fun current(): VfxScene? {
        val current = Minecraft.getInstance().level ?: return null
        if (level !== current) {
            level = current
            scene = VfxScene(VfxWorldEnvironment(current))
        }
        return scene
    }

    fun play(packet: VfxPlayPacket) {
        val scene = current() ?: return
        val position = packet.position ?: packet.entity?.let { Vec3(it.x, it.y, it.z) } ?: Vec3.ZERO
        scene.play(packet.handle, packet.effect, position, packet.entity, packet.data)
    }

    fun stop(handle: Int, immediate: Boolean) {
        scene?.of(handle)?.stop(immediate)
    }

    fun jump(handle: Int, event: String) {
        scene?.of(handle)?.instance?.jumpTo(event)
    }

    fun move(handle: Int, position: Vec3) {
        scene?.of(handle)?.moveTo(position)
    }

    fun applyData(handle: Int, data: CompoundTag) {
        scene?.of(handle)?.applyData(data)
    }

    /** A pack reload replaces every effect definition, so whatever is playing is no longer valid. */
    fun onAssetsReloaded() {
        scene?.clear()
    }
}
