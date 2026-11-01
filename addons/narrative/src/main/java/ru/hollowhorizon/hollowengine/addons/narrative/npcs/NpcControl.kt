package ru.hollowhorizon.hollowengine.addons.narrative.npcs

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.story.SimpleAction
import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryException
import ru.hollowhorizon.hollowengine.common.attachments.api.set
import ru.hollowhorizon.hollowengine.common.attachments.components.Model
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorAsset
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.coroutines.entityRef
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.entities.setNameplate
import ru.hollowhorizon.hollowengine.common.npcs.navigation.MoveOptions
import ru.hollowhorizon.hollowengine.common.npcs.registry.npcs
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs.despawn
import ru.hollowhorizon.hollowengine.common.utils.currentServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val NPC = "hollowengine.gui.ide.story.category.npc"
private const val NAVIGATION = "hollowengine.gui.ide.story.category.navigation"

@EditorCategory(NPC)
@SimpleAction("spawn-npc")
suspend fun ServerLevel.spawnNpc(
    pos: Vec3,
    id: String? = null,
    name: String = "",
    @EditorAsset("gltf", "glb", "geo.json", "fbx", "obj", "bbmodel") model: String = "",
): NpcEntity = npc(pos, id) {
    if (model.isNotEmpty()) set(Model(model))
    if (name.isNotEmpty()) setNameplate(name)
}

@EditorCategory(NPC)
@SimpleAction("despawn-npc")
fun NpcEntity.removeFromWorld() = despawn()

@EditorCategory(NPC)
@SimpleAction("find-npc")
fun findNpc(id: String): NpcEntity =
    currentServer.npcs.alive(id) ?: throw StoryException("No live NPC has the id '$id'")

@EditorCategory(NPC)
@SimpleAction("teleport")
fun teleport(who: Entity, pos: Vec3) = who.teleportTo(pos.x, pos.y, pos.z)

@EditorCategory(NAVIGATION)
@SimpleAction("start-moving")
fun NpcEntity.beginMoving(pos: Vec3, options: MoveOptions = MoveOptions()) {
    startMove(pos, options)
}

@EditorCategory(NAVIGATION)
@SimpleAction("start-following")
fun NpcEntity.beginFollowing(target: Entity, options: MoveOptions = MoveOptions()) {
    startMove(with(server ?: currentServer) { target.entityRef }, options)
}

@EditorCategory(NAVIGATION)
@SimpleAction("start-looking")
fun NpcEntity.beginLooking(pos: Vec3, duration: Duration = 1.seconds, maxAngularSpeed: Float = 360f) {
    startLookingAt(pos, duration, maxAngularSpeed)
}

@EditorCategory(NAVIGATION)
@SimpleAction("start-watching")
fun NpcEntity.beginWatching(target: Entity, duration: Duration = 1.seconds, maxAngularSpeed: Float = 360f) {
    startLookingAt(with(server ?: currentServer) { target.entityRef }, duration, maxAngularSpeed)
}
