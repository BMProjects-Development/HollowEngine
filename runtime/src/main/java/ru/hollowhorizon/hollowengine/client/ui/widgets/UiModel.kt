package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.*
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import kotlinx.coroutines.flow.StateFlow
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.util.Mth
import org.joml.Quaternionf
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.animations.AnimationClip
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.fillAnimationVariables
import ru.hollowhorizon.hollowengine.client.models.internal.manager.ModelLoader
import ru.hollowhorizon.hollowengine.client.models.internal.rendering.RenderContext
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelInstance
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.render.*
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.common.attachments.components.AnimationsComponent
import ru.hollowhorizon.hollowengine.common.models.AnimationExpression
import ru.hollowhorizon.hollowengine.common.models.AnimationPlayMode
import ru.hollowhorizon.hollowengine.common.models.ClipAnimationLayerSpec
import ru.hollowhorizon.hollowengine.common.models.LayerBlendMode
import ru.hollowhorizon.hollowengine.common.utils.Color
import ru.hollowhorizon.hollowengine.common.utils.isValidRL
import ru.hollowhorizon.hollowengine.common.utils.math.*
import ru.hollowhorizon.hollowengine.common.utils.rl
import kotlin.math.hypot
import kotlin.math.min

/**
 * Holds everything a [Model] preview needs: which model to show, the orbit-camera parameters (yaw /
 * pitch / zoom / pan) and the display toggles.
 */
@Stable
class ModelViewerState(model: String) {
    var model: String by mutableStateOf(model)
        private set

    var instance: ModelInstance by mutableStateOf(loadInstance(model))
        private set

    val attachment: ModelAttachment get() = instance.attachment

    var yaw by mutableStateOf(0f)
    var pitch by mutableStateOf(0f)
    var zoom by mutableStateOf(1f)
    var offsetX by mutableStateOf(0f)
    var offsetY by mutableStateOf(0f)

    var showGrid by mutableStateOf(true)
    var showBoundingBox by mutableStateOf(false)
    var showWireframe by mutableStateOf(false)
    var autoRotate by mutableStateOf(false)

    var selectedAnimation by mutableStateOf(0)

    private val playing = mutableStateListOf<Int>()
    private val animationWeights = mutableMapOf<String, Float>()
    private val animationContext = AnimatorEvaluationContext()
    private var configuredAnimationNames = emptyList<String>()
    private var animationWeightVariables = emptyList<String>()

    var nodeVisibilityRevision by mutableStateOf(0)
        private set

    var debugDraw: ((DebugLines.Batch) -> Unit)? = null
    private var lastRect = UiRect.Zero

    val modelFlow: StateFlow<Model> get() = attachment.flow

    /** The model's runtime node hierarchy (roots), for inspection UIs. */
    val nodes: List<RuntimeNode>
        get() {
            attachment.ensureReady()
            return attachment.nodes
        }

    /** Snapshot of the loaded animations (empty while the model is still streaming in). */
    val animations: List<AnimationClip>
        get() {
            attachment.ensureReady()
            return attachment.animations.toList()
        }

    val triangles: Int
        get() {
            attachment.ensureReady()
            return attachment.triangles
        }

    val shapekeys: Int
        get() {
            attachment.ensureReady()
            return attachment.shapekeys
        }

    val currentAnimation: AnimationClip? get() = animations.getOrNull(selectedAnimation)

    val currentAnimationProgress: Float
        get() {
            val animation = currentAnimation ?: return 0f
            if (animation.duration <= 0f) return 0f
            return ((instance.animator.layerTime(previewLayerId(animation.name)) ?: 0f) / animation.duration).coerceIn(
                    0f,
                    1f
                )
        }

    fun isPlaying(index: Int): Boolean = index in playing

    /** Whether anything is still moving (auto-rotate, active playback, or a weight still fading). */
    fun isAnimating(): Boolean =
        autoRotate || playing.isNotEmpty() || animationWeights.values.any { it > ANIMATION_WEIGHT_EPSILON }

    fun selectAnimation(index: Int) {
        val count = animations.size
        if (count == 0) {
            selectedAnimation = 0
            return
        }
        selectedAnimation = ((index % count) + count) % count
    }

    /** Toggles playback of the current animation; render() fades its weight in/out over [AnimBlendTime]. */
    fun togglePlayback() {
        val index = selectedAnimation
        if (animations.getOrNull(index) == null) return
        if (index in playing) {
            playing.remove(index)
        } else {
            playing.add(index)
        }
    }

    /** Toggles a node's visibility (cascades to its children) and refreshes any tree UI. */
    fun toggleNodeVisibility(node: RuntimeNode) {
        node.isVisible = !node.isVisible
        nodeVisibilityRevision++
    }

    /**
     * Where a point of the model lands on screen, relative to the panel.
     */
    fun project(point: Vec3f): Vec3f = MutableVec3f().also { viewMatrix().transform(point, 1f, it) }

    /**
     * The bones under the pointer, nearest first.
     */
    fun bonesAt(x: Float, y: Float, radius: Float = PICK_RADIUS): List<RuntimeNode> {
        val matrix = viewMatrix()
        val head = MutableVec3f()
        val tail = MutableVec3f()

        return SkeletonLayout.of(attachment).mapNotNull { bone ->
                matrix.transform(bone.head, 1f, head)
                matrix.transform(bone.tail, 1f, tail)
                val distance = distanceToSegment(x, y, head, tail)
                if (distance <= radius) bone.node to distance else null
            }.sortedBy { it.second }.map { it.first }
    }

    private fun distanceToSegment(x: Float, y: Float, head: Vec3f, tail: Vec3f): Float {
        val dx = tail.x - head.x
        val dy = tail.y - head.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared < 1.0e-6f) return hypot(head.x - x, head.y - y)

        val along = (((x - head.x) * dx + (y - head.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(head.x + dx * along - x, head.y + dy * along - y)
    }

    private fun viewMatrix(): Mat4f {
        val baseSize = min(lastRect.width, lastRect.height)
        val scale = baseSize * zoom
        return MutableMat4f().translate(lastRect.width / 2f + offsetX * zoom, lastRect.height / 2f + offsetY * zoom, 0f)
            .scale(Vec3f(scale, -scale, scale)).rotate(pitch.deg, Vec3f.X_AXIS)
            .rotate((yaw + PREVIEW_FRONT_YAW).deg, Vec3f.Y_AXIS)
    }

    /** Renders the model into [rect] using [stack]; called from a `drawGl` block on the render thread. */
    fun render(rect: UiRect, stack: PoseStack) {
        lastRect = rect
        if (autoRotate) yaw = (yaw + 20f * TickHandler.deltaFrameTime) % 360f

        val step = (TickHandler.deltaFrameTime / AnimBlendTime).coerceIn(0f, 1f)
        val animations = animations
        val activeAnimationNames = animations.mapTo(HashSet()) { it.name }
        animationWeights.keys.removeIf { it !in activeAnimationNames }
        animations.forEachIndexed { index, animation ->
            val target = if (index in playing) 1f else 0f
            val weight = animationWeights[animation.name] ?: 0f
            val nextWeight = weight + (target - weight) * step
            if (target == 0f && nextWeight <= ANIMATION_WEIGHT_EPSILON) {
                animationWeights.remove(animation.name)
            } else {
                animationWeights[animation.name] = nextWeight
            }
        }

        configurePreviewAnimator(animations)

        fillAnimationVariables(animationContext, null, TickHandler.partialTick)
        animationContext.time = TickHandler.gameTime
        animations.indices.forEach { index ->
            animationContext.variables[animationWeightVariables[index]] = animationWeights[animations[index].name] ?: 0f
        }
        instance.update(animationContext)

        GL33.glDepthFunc(GL33.GL_LEQUAL)

        val centerX = rect.x + rect.width / 2f
        val centerY = rect.y + rect.height / 2f
        stack.translate(centerX + offsetX * zoom, centerY + offsetY * zoom, 0f)

        val baseSize = min(rect.width, rect.height)
        val scale = baseSize * zoom
        stack.scale(scale, -scale, scale)
        stack.mulPose(Quaternionf().rotateX(pitch * Mth.DEG_TO_RAD))
        stack.mulPose(Quaternionf().rotateY((yaw + PREVIEW_FRONT_YAW) * Mth.DEG_TO_RAD))

        val attachment = attachment
        val bounds = if (showBoundingBox) attachment.calculateBounds() else null

        if (showWireframe) GL33.glPolygonMode(GL33.GL_FRONT_AND_BACK, GL33.GL_LINE)
        val savedTextures = if (showWireframe) {
            attachment.materials.map { material ->
                val old = material.texture
                material.texture = "${HollowEngine.MODID}:default_color_map".rl
                material to old
            }
        } else emptyList()

        RenderSystem.setShaderLights(CUSTOM_IMGUI_LIGHT_0, CUSTOM_IMGUI_LIGHT_1)

        val bufferSource = Minecraft.getInstance().renderBuffers().bufferSource()
        attachment.pipeline.render(
            RenderContext(stack, bufferSource, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY)
        )
        bufferSource.endBatch()

        if (showWireframe) {
            GL33.glPolygonMode(GL33.GL_FRONT_AND_BACK, GL33.GL_FILL)
            savedTextures.forEach { (material, texture) -> material.texture = texture }
        }

        bounds?.let { (minCorner, maxCorner) ->
            OpenGLUtils.renderBoundingBox(stack, minCorner, maxCorner, Color.WHITE.withAlpha(0.75f))
        }

        debugDraw?.let { draw ->
            draw(DebugLines.batch(bufferSource, stack, DebugLines.PANEL))
            bufferSource.endBatch(DebugLines.PANEL)
        }
    }

    /** Rebuilds only when the loaded model's animation set changes, including resource hot reloads. */
    private fun configurePreviewAnimator(animations: List<AnimationClip>) {
        if (animations.size == configuredAnimationNames.size && animations.indices.all { animations[it].name == configuredAnimationNames[it] }) return

        val names = animations.map { it.name }
        configuredAnimationNames = names
        animationWeightVariables = names.indices.map(::previewWeightVariable)
        animationContext.variables.clear()
        instance.animator.configure(
            model = null, animations = AnimationsComponent(
                clips = names.mapIndexed { index, animation ->
                    ClipAnimationLayerSpec(
                        id = previewLayerId(animation),
                        animation = animation,
                        playMode = AnimationPlayMode.Loop,
                        weight = AnimationExpression("v.${animationWeightVariables[index]}"),
                        blendMode = LayerBlendMode.Additive,
                    )
                },
            )
        )
    }

    private fun loadInstance(model: String): ModelInstance = ModelInstance(
        try {
        require(model.isValidRL()) { "Invalid model id: $model" }
        ModelAttachment(model)
    } catch (_: Exception) {
        ModelAttachment(ModelLoader.FALLBACK_MODEL)
    })

    companion object {
        private const val PREVIEW_FRONT_YAW = 180f
    }
}

private fun previewLayerId(animation: String): String = "preview:$animation"

private fun previewWeightVariable(index: Int): String = "preview_animation_${index}_weight"

/** Seconds an animation takes to fade fully in or out when toggled. */
private const val AnimBlendTime = 0.25f
private const val ANIMATION_WEIGHT_EPSILON = 0.001f

private const val PICK_RADIUS = 7f

private const val ModelGridSpacing = 36f
private val ModelGridColor = UiColor(0.62f, 0.7f, 0.85f, 0.14f)

/**
 * A 3D model preview: renders [state]'s [ModelAttachment] through the raw-GL
 * canvas primitive ([drawBehind] + `drawGl`).
 */
@Composable
fun Model(
    state: ModelViewerState,
    id: String? = null,
    tags: Iterable<String> = emptyList(),
    modifier: Modifier? = null,
    onDrag: ((UiEvent) -> Boolean)? = null,
) {
    Box(
        id = id,
        tags = tags,
        modifier = Modifier.input(hoverable = true, draggable = true).cursor(UiCursorShape.HAND).onDrag { event ->
                if (onDrag?.invoke(event) == true) {
                    event.consume()
                } else if (event.button == 1) {
                    val scale = state.zoom.coerceAtLeast(0.0001f)
                    state.offsetX += event.deltaX / scale
                    state.offsetY += event.deltaY / scale
                } else {
                    state.yaw = (state.yaw + event.deltaX / 3f) % 360f
                    state.pitch = (state.pitch + event.deltaY / 3f).coerceIn(-90f, 90f)
                }
                event.consume()
            }.onScroll { event ->
                val factor = if (event.scrollY > 0f) 0.9f else 1.1f
                state.zoom = (state.zoom * factor).coerceIn(0.1f, 10f)
                event.consume()
            }.drawBehind(key = state) {
                if (state.showGrid) drawGrid(state)
                drawGl { state.render(rect, poseStack) }
            }.then(modifier ?: Modifier),
    )
}

/** Draws a faint pan/zoom-following reference grid behind the model, in node-local coordinates. */
private fun UiCanvasDrawScope.drawGrid(state: ModelViewerState) {
    val width = size.width
    val height = size.height
    if (width <= 0f || height <= 0f) return

    val spacing = (ModelGridSpacing * state.zoom).coerceIn(8f, 512f)
    val paint = UiPaint.Color(ModelGridColor)

    var x = (state.offsetX * state.zoom + width / 2f) % spacing
    if (x < 0f) x += spacing
    while (x < width) {
        drawRect(UiRect(x, 0f, 1f, height), paint)
        x += spacing
    }

    var y = (state.offsetY * state.zoom + height / 2f) % spacing
    if (y < 0f) y += spacing
    while (y < height) {
        drawRect(UiRect(0f, y, width, 1f), paint)
        y += spacing
    }
}
