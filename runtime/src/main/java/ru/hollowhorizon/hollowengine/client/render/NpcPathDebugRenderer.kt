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
        paths[packet.entityId] = DebugPath(path, jumps, packet.steeringTarget, packet.lookTarget, packet.speedShare, Util.getMillis())
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
            renderMarker(event, bufferSource, debugPath.steeringTarget, MARKER_SIZE, 0.0f, 1.0f, 1.0f)
            debugPath.lookTarget?.let { renderMarker(event, bufferSource, it, LOOK_MARKER_SIZE, 1.0f, 0.2f, 1.0f) }
            val heading = debugPath.steeringTarget
            DebugRenderer.renderFloatingText(
                event.poseStack, bufferSource, "x%.2f".format(debugPath.speedShare),
                heading.x, heading.y + MARKER_HEIGHT + SHARE_TEXT_LIFT, heading.z, SHARE_TEXT_COLOR,
            )
            renderJumps(event, bufferSource, debugPath)
        }
        bufferSource.endBatch()
    }

    /** A post at [target]: cyan where the NPC heads, magenta where it looks. */
    private fun renderMarker(
        event: RenderLevelStageEvent,
        bufferSource: MultiBufferSource,
        target: NpcPathDebugPoint,
        size: Double,
        red: Float,
        green: Float,
        blue: Float,
    ) {
        val camera = event.camera.position
        val bounds = AABB.ofSize(
            Vec3(target.x, target.y + MARKER_HEIGHT * 0.5, target.z),
            size,
            MARKER_HEIGHT,
            size,
        ).move(-camera.x, -camera.y, -camera.z)
        DebugRenderer.renderFilledBox(event.poseStack, bufferSource, bounds, red, green, blue, 0.8f)
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
        val lookTarget: NpcPathDebugPoint?,
        val speedShare: Float,
        val updatedAt: Long,
    )

    private const val PATH_TIMEOUT_MS = 2_000L
    private const val NODE_RADIUS = 0.3f
    private const val MARKER_SIZE = 0.2
    private const val MARKER_HEIGHT = 0.7
    private const val LOOK_MARKER_SIZE = 0.1
    private const val SHARE_TEXT_LIFT = 0.3
    private const val SHARE_TEXT_COLOR = 0xFFFFFF
    private const val ARC_SEGMENTS = 12
    private const val ARC_HEIGHT = 1.25f
    private const val JUMP_COLOR = 0xFFFFAA00.toInt()
}
