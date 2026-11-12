package ru.hollowhorizon.hollowengine.client.ui.ide.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexSorting
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import net.minecraft.client.Camera
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ChunkPos
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import ru.hollowhorizon.hollowengine.bridge.client.CameraTransforms
import ru.hollowhorizon.hollowengine.bridge.client.WorldPreviewTargets
import ru.hollowhorizon.hollowengine.bridge.mixins.client.CameraInvoker
import ru.hollowhorizon.hollowengine.bridge.mixins.client.LevelRendererInvoker
import ru.hollowhorizon.hollowengine.bridge.mixins.client.LightTextureAccessor
import ru.hollowhorizon.hollowengine.client.camera.CameraPose
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeOverlay
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdeScale
import ru.hollowhorizon.hollowengine.common.network.CAMERA_CHUNK_RADIUS
import ru.hollowhorizon.hollowengine.common.utils.ModList
import ru.hollowhorizon.hollowengine.fabric.internal.rendering.IrisPreviewPipeline
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

internal object WorldCameraPreviewRenderer {
    private val views = CopyOnWriteArraySet<WorldPreviewView>()
    private val resources = LinkedHashMap<WorldPreviewView, ViewResources>()
    private val camera = Camera()
    private val chunkStreaming = WorldPreviewChunkStreaming()
    private val playerSections = ObjectArrayList<SectionRenderDispatcher.RenderSection>()
    private val sodium by lazy { ModList.isLoaded("sodium") }
    private var shadersInvalidated = false
    private var guideView: Pair<Camera, Matrix4f>? = null
    var activeCamera: Camera? = null
        private set
    var isRendering: Boolean = false
        private set
    var status by mutableStateOf(WorldCameraPreviewStatus.NO_WORLD)
        private set

    fun add(view: WorldPreviewView) {
        views += view
    }

    fun remove(view: WorldPreviewView) {
        views -= view
        view.texture = 0
    }

    fun invalidateShaderPipelines() {
        shadersInvalidated = true
    }

    fun markSectionDirty(x: Int, y: Int, z: Int, immediate: Boolean) {
        resources.values.forEach { it.targets.terrain()?.setDirty(x, y, z, immediate) }
    }

    fun release(minecraft: Minecraft) {
        chunkStreaming.stop(minecraft)
        for ((view, resources) in resources) {
            view.texture = 0
            view.guide = null
            resources.close()
        }
        resources.clear()
        guideView = null
        camera.reset()
    }

    fun captureGuideView(camera: Camera, projection: Matrix4f) {
        if (!isRendering && views.isNotEmpty() && HollowIdeOverlay.showsEditorSurfaces()) {
            guideView = camera to Matrix4f(projection)
        }
    }

    fun drawGuides() {
        val (camera, projection) = guideView ?: return
        guideView = null
        drawWorldCameraGuides(camera, projection, views.mapNotNull { it.guide })
    }

    fun entityBuffers(entity: Entity, partialTick: Float, buffers: MultiBufferSource): MultiBufferSource =
        activeCamera?.let { fadeIntersectingEntity(it.position, entity, partialTick, buffers) } ?: buffers

    fun entityOpacity(entity: Entity, partialTick: Float): Float =
        activeCamera?.let { previewEntityOpacity(it.position, entity, partialTick) } ?: 1f

    fun beginFrame(minecraft: Minecraft) {
        guideView = null
        if (views.isEmpty() && resources.isEmpty()) return
        status = when {
            minecraft.level == null -> WorldCameraPreviewStatus.NO_WORLD
            else -> WorldCameraPreviewStatus.READY
        }
        val enabled = HollowIdeOverlay.showsEditorSurfaces() && status == WorldCameraPreviewStatus.READY
        val iterator = resources.iterator()
        while (iterator.hasNext()) {
            val (view, resources) = iterator.next()
            if (enabled && view in views) {
                if (shadersInvalidated) resources.shader.close()
                continue
            }
            view.texture = 0
            view.guide = null
            resources.close()
            iterator.remove()
        }
        shadersInvalidated = false
        if (!enabled || views.isEmpty()) {
            chunkStreaming.update(minecraft, emptyList())
            camera.reset()
        }
    }

    fun render(minecraft: Minecraft, delta: DeltaTracker) {
        if (views.isEmpty() || !HollowIdeOverlay.showsEditorSurfaces() || status != WorldCameraPreviewStatus.READY) return
        val level = minecraft.level ?: return
        val player = minecraft.player ?: return
        val main = minecraft.mainRenderTarget
        val renderer = minecraft.gameRenderer
        val levelRenderer = minecraft.levelRenderer
        val levelRendererAccess = levelRenderer as LevelRendererInvoker
        val visibleSections = levelRendererAccess.`hollowengine$getVisibleSections`()
        val dispatcher = levelRendererAccess.`hollowengine$getSectionRenderDispatcher`()
        val previewDistance = minOf(CAMERA_CHUNK_RADIUS, minecraft.options.effectiveRenderDistance)
        val light = renderer.lightTexture()
        val lightState = light as LightTextureAccessor
        val partialTick = delta.getGameTimeDeltaPartialTick(true)
        val depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
        val readFramebuffer = GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        val previousProjection = Matrix4f(RenderSystem.getProjectionMatrix())
        val sorting = RenderSystem.getVertexSorting()
        val centers = LinkedHashSet<ChunkPos>()

        playerSections.addAll(visibleSections)
        isRendering = true
        try {
            for (view in views) {
                val bounds = view.bounds ?: continue
                if (bounds.width <= 0f || bounds.height <= 0f) continue
                // Iris's final pass leaves depth writes off. The next view must be able to clear its depth buffers.
                RenderSystem.depthMask(true)
                val scale = HollowIdeScale.factor()
                val width = (bounds.width * scale).roundToInt().coerceAtLeast(1)
                val height = (bounds.height * scale).roundToInt().coerceAtLeast(1)
                val frame = view.frame()
                val pose = frame.pose
                val aspect = width.toFloat() / height
                view.guide = frame.cameraPath?.let { WorldCameraGuide(pose, aspect, it) }
                val projection = Matrix4f().perspective(
                    Math.toRadians(pose.fov.coerceIn(1f, 179f).toDouble()).toFloat(),
                    aspect,
                    NEAR_PLANE,
                    renderer.depthFar.coerceAtLeast(1f),
                )
                val resources = resources.getOrPut(view) { ViewResources(width, height) }
                val targets = resources.targets
                val shader = resources.shader
                targets.resize(width, height)
                if (!sodium && dispatcher != null) targets.updateTerrain(level, dispatcher, previewDistance)
                val restoreEnvironment = frame.environment?.apply(level)
                minecraft.profiler.push("ide_camera_preview")
                try {
                    prepareCamera(level, player, partialTick, pose)
                    centers.add(ChunkPos(camera.blockPosition))
                    activeCamera = camera
                    targets.bind()
                    shader.bind()
                    if (restoreEnvironment != null) lightState.`hollowengine$setDirty`(true)
                    light.updateLightTexture(partialTick)
                    val viewMatrix = Matrix4f().rotation(camera.rotation().conjugate(Quaternionf()))
                    RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN)
                    targets.target().bindWrite(true)
                    if (sodium) levelRenderer.needsUpdate()
                    levelRenderer.prepareCullFrustum(camera.position, viewMatrix, projection)
                    levelRenderer.renderLevel(delta, false, camera, renderer, light, viewMatrix, projection)
                    shader.finish()
                    if (view in views) view.texture = targets.target().colorTextureId
                } finally {
                    shader.unbind()
                    targets.unbind()
                    activeCamera = null
                    minecraft.profiler.pop()
                    if (restoreEnvironment != null) {
                        restoreEnvironment()
                        lightState.`hollowengine$setDirty`(true)
                    }
                }
            }
        } finally {
            visibleSections.clear()
            visibleSections.addAll(playerSections)
            playerSections.clear()
            if (sodium) levelRenderer.needsUpdate()
            RenderSystem.setProjectionMatrix(previousProjection, sorting)
            main.bindWrite(true)
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer)
            RenderSystem.depthMask(depthWrite)
            isRendering = false
        }
        chunkStreaming.update(minecraft, centers)
    }

    private fun prepareCamera(level: ClientLevel, player: Entity, partialTick: Float, pose: CameraPose) {
        camera.setup(level, player, false, false, partialTick)
        val accessor = camera as CameraInvoker
        accessor.`hollowengine$setDetached`(true)
        accessor.`hollowcore$setPosition`(
            pose.position.x.toDouble(), pose.position.y.toDouble(), pose.position.z.toDouble()
        )
        CameraTransforms.setRotation(camera, pose.yaw, pose.pitch, pose.roll)
    }

    private class ViewResources(width: Int, height: Int) : AutoCloseable {
        val targets = WorldPreviewTargets(width, height)
        val shader = IrisPreviewPipeline()

        override fun close() {
            shader.close()
            targets.close()
        }
    }

    private const val NEAR_PLANE = 0.05f
}
