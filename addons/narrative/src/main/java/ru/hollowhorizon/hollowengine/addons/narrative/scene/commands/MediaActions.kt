package ru.hollowhorizon.hollowengine.addons.narrative.scene.commands

import kotlinx.coroutines.delay
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.story.SimpleAction
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorAsset
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.effects.playSound
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.effects.playVfx
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.effects.stopSound
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.effects.updateSound
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.entities.*
import ru.hollowhorizon.hollowengine.common.vfx.VfxFormat
import ru.hollowhorizon.hollowengine.common.vfx.VfxHandle
import kotlin.time.Duration.Companion.milliseconds

private const val SOUND = "hollowengine.gui.ide.story.category.effects.sound"
private const val VFX = "hollowengine.gui.ide.story.category.effects.vfx"
private const val MATERIALS = "hollowengine.gui.ide.story.category.effects.materials"
private const val SCREEN = "hollowengine.gui.ide.story.category.effects.screen"
private const val ANIMATION = "hollowengine.gui.ide.story.category.animation"
private const val TIME = "hollowengine.gui.ide.story.category.time"

private const val TICK_MILLIS = 50L

/** The effects a story started by name, for a node to stop them later: a handle cannot be saved with the story. */
private val namedVfx = HashMap<String, VfxHandle>()

@EditorCategory(SOUND)
@SimpleAction("play-sound-named")
fun ServerLevel.playNamedSound(id: String, @EditorAsset("ogg", "wav", "mp3") sound: String, pos: Vec3? = null, volume: Float = 1f, pitch: Float = 1f) {
    playSound(sound) {
        this.id = id
        this.volume = volume
        this.pitch = pitch
        this.position = pos
    }
}

@EditorCategory(SOUND)
@SimpleAction("stop-sound")
fun ServerLevel.stopNamedSound(id: String, fadeOut: Float = 0f) = stopSound(id, fadeOut)

@EditorCategory(SOUND)
@SimpleAction("change-sound")
fun ServerLevel.changeNamedSound(id: String, volume: Float = 1f, pitch: Float = 1f) {
    updateSound(id, volume, pitch)
}

@EditorCategory(VFX)
@SimpleAction("play-vfx")
fun ServerLevel.playNamedVfx(id: String, @EditorAsset(VfxFormat.EXTENSION) effect: String, pos: Vec3) {
    namedVfx.put(id, playVfx(pos, effect))?.stop()
}

@EditorCategory(VFX)
@SimpleAction("play-vfx-on")
fun Entity.playNamedVfx(id: String, @EditorAsset(VfxFormat.EXTENSION) effect: String) {
    namedVfx.put(id, playVfx(effect))?.stop()
}

@EditorCategory(VFX)
@SimpleAction("move-vfx")
fun moveNamedVfx(id: String, pos: Vec3) {
    namedVfx[id]?.moveTo(pos)
}

@EditorCategory(VFX)
@SimpleAction("stop-vfx")
fun stopNamedVfx(id: String, immediate: Boolean = false) {
    namedVfx.remove(id)?.stop(immediate)
}

@EditorCategory(SCREEN)
@SimpleAction("play-video")
fun ServerPlayer.playVideo(@EditorAsset(".mp4", ".webm", ".mkv", ".mov", ".avi") source: String) = PlayVideoPacket(source).send(this)

@EditorCategory(MATERIALS)
@SimpleAction("set-material")
fun Entity.setMaterial(material: String, @EditorAsset("png") texture: String) {
    materials[material] = texture(texture)
}

@EditorCategory(MATERIALS)
@SimpleAction("reset-material")
fun Entity.resetMaterial(material: String) = materials.reset(material)

@EditorCategory(MATERIALS)
@SimpleAction("reset-materials")
fun Entity.resetMaterials() = materials.clear()

@EditorCategory(ANIMATION)
@SimpleAction("play-montage")
suspend fun Entity.playMontageToEnd(montage: String, fadeIn: Double = 0.2, fadeOut: Double = 0.2, speed: Double = 1.0) {
    playMontage(montage, fadeIn, fadeOut, speed)
}

@EditorCategory(ANIMATION)
@SimpleAction("start-montage")
fun Entity.beginMontage(montage: String, fadeIn: Double = 0.2, fadeOut: Double = 0.2, speed: Double = 1.0) {
    startMontage(montage, fadeIn, fadeOut, speed)
}

@EditorCategory(ANIMATION)
@SimpleAction("stop-montage")
fun Entity.endMontage(montage: String, fadeOut: Double = 0.2) = stopMontage(montage, fadeOut)

@EditorCategory(ANIMATION)
@SimpleAction("stop-all-animations")
fun Entity.endAllAnimations() = stopAllAnimations()

@EditorCategory(TIME)
@SimpleAction("next-tick")
suspend fun nextTick() = delay(TICK_MILLIS.milliseconds)
