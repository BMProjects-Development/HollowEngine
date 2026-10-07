package ru.hollowhorizon.hollowengine.client.render

import net.minecraft.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.debug.DebugRenderer
import net.minecraft.client.renderer.debug.PathfindingRenderer
import net.minecraft.core.BlockPos
import net.minecraft.world.level.pathfinder.Node
import net.minecraft.world.level.pathfinder.Path
import net.minecraft.world.level.pathfinder.PathType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderLevelStageEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderStage
import ru.hollowhorizon.hollowengine.common.npcs.navigation.NpcPathDebugPacket
import ru.hollowhorizon.hollowengine.common.npcs.navigation.NpcPathDebugPoint
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

@ClientOnly
object NpcPathDebugRenderer {
    private val paths = mutableMapOf<Int, DebugPath>()

    fun update(packet: NpcPathDebugPacket) {
        if (packet.nodes.isEmpty()) {
            paths.remove(packet.entityId)
            return
        }

        val nodes = packet.nodes.map { debugNode ->
            Node(debugNode.x, debugNode.y, debugNode.z).apply {
                type = PathType.valueOf(debugNode.type)
                costMalus = debugNode.costMalus
            }
        }
        val path = Path(
            nodes,
            BlockPos(packet.targetX, packet.targetY, packet.targetZ),
            packet.reached,
        ).apply {
            nextNodeIndex = packet.nextNodeIndex.coerceIn(0, nodes.lastIndex)
        }
        val jumps = packet.nodes.indices.filter { packet.nodes[it].jump }
        paths[packet.entityId] = DebugPath(path, jumps, packet.steeringTarget, Util.getMillis())
    }

    @SubscribeEvent
    fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderStage.AFTER_ENTITIES || paths.isEmpty()) return

        val now = Util.getMillis()
        paths.values.removeIf { now - it.updatedAt > PATH_TIMEOUT_MS }
        if (paths.isEmpty()) return

        val camera = event.camera.position
        val bufferSource = Minecraft.getInstance().renderBuffers().bufferSource()
        for ((_, debugPath) in paths) {
            PathfindingRenderer.renderPath(
                event.poseStack,
                bufferSource,
                debugPath.path,
                NODE_RADIUS,
                false,
                true,
                camera.x,
                camera.y,
                camera.z,
            )
            renderSteeringTarget(event, bufferSource, debugPath.steeringTarget)
            renderJumps(event, bufferSource, debugPath)
        }
        bufferSource.endBatch()
    }

    private fun renderSteeringTarget(
        event: RenderLevelStageEvent,
        bufferSource: MultiBufferSource,
        target: NpcPathDebugPoint,
    ) {
        val camera = event.camera.position
        val bounds = AABB.ofSize(
            Vec3(target.x, target.y + MARKER_HEIGHT * 0.5, target.z),
            MARKER_SIZE,
            MARKER_HEIGHT,
            MARKER_SIZE,
        ).move(-camera.x, -camera.y, -camera.z)
        DebugRenderer.renderFilledBox(event.poseStack, bufferSource, bounds, 0.0f, 1.0f, 1.0f, 0.8f)
    }

    private fun renderJumps(event: RenderLevelStageEvent, bufferSource: MultiBufferSource, debugPath: DebugPath) {
        if (debugPath.jumps.isEmpty()) return
        val camera = event.camera.position
        val lines = DebugLines.batch(bufferSource, event.poseStack)
        for (index in debugPath.jumps) {
            if (index == 0) continue
            val from = debugPath.path.getNode(index - 1)
            val to = debugPath.path.getNode(index)
            var previous = arcPoint(from, to, 0f, camera)
            for (step in 1..ARC_SEGMENTS) {
                val point = arcPoint(from, to, step.toFloat() / ARC_SEGMENTS, camera)
                lines.line(previous, point, JUMP_COLOR)
                previous = point
            }
        }
    }

    private fun arcPoint(from: Node, to: Node, t: Float, camera: Vec3): Vec3f {
        val height = from.y + (to.y - from.y) * t + ARC_HEIGHT * 4f * t * (1f - t)
        return Vec3f(
            (from.x + 0.5f + (to.x - from.x) * t - camera.x).toFloat(),
            (height - camera.y).toFloat(),
            (from.z + 0.5f + (to.z - from.z) * t - camera.z).toFloat(),
        )
    }

    private data class DebugPath(
        val path: Path,
        val jumps: List<Int>,
        val steeringTarget: NpcPathDebugPoint,
        val updatedAt: Long,
    )

    private const val PATH_TIMEOUT_MS = 2_000L
    private const val NODE_RADIUS = 0.3f
    private const val MARKER_SIZE = 0.2
    private const val MARKER_HEIGHT = 0.7
    private const val ARC_SEGMENTS = 12
    private const val ARC_HEIGHT = 1.25f
    private const val JUMP_COLOR = 0xFFFFAA00.toInt()
}
