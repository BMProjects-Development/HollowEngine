package ru.hollowhorizon.hollowengine.client.ui.ide.panels

import androidx.compose.runtime.*
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexSorting
import kotlinx.coroutines.isActive
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.Sheets
import net.minecraft.client.renderer.block.model.BlockModel
import net.minecraft.client.renderer.block.model.ItemModelGenerator
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.resources.model.*
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.util.Mth
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.handlers.TickHandler
import ru.hollowhorizon.hollowengine.client.render.CUSTOM_IMGUI_LIGHT_0
import ru.hollowhorizon.hollowengine.client.render.CUSTOM_IMGUI_LIGHT_1
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover
import ru.hollowhorizon.hollowengine.client.utils.lang
import java.util.function.Function
import kotlin.math.abs
import kotlin.math.min

private const val GridIcon = "hollowengine:textures/gui/icons/graph.svg"
private const val AutoRotateIcon = "hollowengine:textures/gui/icons/reload.svg"
private const val ReloadIcon = "hollowengine:textures/gui/icons/load.svg"

/** Interactive preview for the standard Minecraft JSON model format. */
@Composable
internal fun VanillaModelEditorPanel(path: String) {
    val resource = remember(path) { path.toAssetResourceLocation() }
    val state = remember(resource) { VanillaModelViewerState(resource, Minecraft.getInstance().resourceManager) }

    Box(
        tags = listOf("model-editor-root", "vanilla-model-editor"),
        modifier = Modifier.style("hollowengine:ui/styles/model-editor.hss").size(100.percent, 100.percent),
    ) {
        VanillaModelPreview(state, modifier = Modifier.size(100.percent, 100.percent))
        Text(resource.toString(), tags = listOf("model-title"))
        Column(tags = listOf("model-toolbar")) {
            VanillaModelToggle(GridIcon, VanillaModelLang.REFERENCE_GRID.lang, state.showGrid) {
                state.showGrid = !state.showGrid
            }
            VanillaModelToggle(AutoRotateIcon, VanillaModelLang.AUTO_ROTATE.lang, state.autoRotate) {
                state.autoRotate = !state.autoRotate
            }
            VanillaModelToggle(
                ReloadIcon,
                VanillaModelLang.RELOAD.lang,
                active = false,
                onToggle = state::reload,
            )
        }
        state.error?.let { error ->
            Text(error, tags = listOf("vanilla-model-error"), modifier = Modifier.textWrap())
        }
    }
}

@Composable
private fun VanillaModelToggle(icon: String, tooltip: String, active: Boolean, onToggle: () -> Unit) {
    Box(
        tags = if (active) listOf("model-chip", "selected") else listOf("model-chip"),
        modifier = Modifier.cursor(UiCursorShape.HAND).onClick { event ->
            if (event.isLeftClick()) onToggle()
            event.consume()
        }.tooltipOnHover(tooltip),
    ) {
        Image(icon, tags = if (active) listOf("model-chip-icon", "selected") else listOf("model-chip-icon"))
    }
}

@Composable
private fun VanillaModelPreview(state: VanillaModelViewerState, modifier: Modifier = Modifier) {
    LaunchedEffect(state) {
        while (isActive) withFrameNanos(state.zoomSpring::advance)
    }
    Box(
        modifier = Modifier
            .input(hoverable = true, draggable = true)
            .cursor(UiCursorShape.HAND)
            .onDrag { event ->
                if (event.button == 1) {
                    val scale = state.zoom.coerceAtLeast(0.0001f)
                    state.offsetX += event.deltaX / scale
                    state.offsetY += event.deltaY / scale
                } else {
                    state.yaw = (state.yaw + event.deltaX / 3f) % 360f
                    state.pitch = (state.pitch + event.deltaY / 3f).coerceIn(-90f, 90f)
                }
                event.consume()
            }
            .onScroll { event ->
                state.zoomSpring.scroll(event.scrollY)
                event.consume()
            }
            .drawBehind(key = state) {
                if (state.showGrid) drawVanillaModelGrid(state)
                drawGl { state.render(rect, poseStack) }
            }
            .then(modifier),
    )
}

@Stable
private class VanillaModelViewerState(
    private val resource: ResourceLocation,
    private val resourceManager: ResourceManager,
) {
    var yaw by mutableStateOf(35f)
    var pitch by mutableStateOf(25f)
    val zoomSpring = SpringZoom(0.72f, min = 0.1f, max = 10f, perNotch = ModelZoomPerNotch)
    val zoom: Float get() = zoomSpring.value
    var offsetX by mutableStateOf(0f)
    var offsetY by mutableStateOf(0f)
    var showGrid by mutableStateOf(true)
    var autoRotate by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
        private set

    private var bakedModel by mutableStateOf<BakedModel?>(null)

    init {
        reload()
    }

    fun reload() {
        runCatching {
            VanillaModelBaker(resourceManager).bakeResource(resource)
        }.onSuccess { model ->
            bakedModel = model
            error = if (model.isCustomRenderer) VanillaModelLang.BUILTIN_RENDERER.lang else null
        }.onFailure { failure ->
            bakedModel = null
            error = failure.message ?: VanillaModelLang.LOAD_FAILED.lang(resource)
        }
    }

    fun render(rect: UiRect, stack: PoseStack) {
        val model = bakedModel ?: return
        if (autoRotate) yaw = (yaw + AutoRotateDegreesPerSecond * TickHandler.deltaFrameTime) % 360f

        val previousProjection = Matrix4f(RenderSystem.getProjectionMatrix())
        val previousSorting = RenderSystem.getVertexSorting()
        val logicalWidth = (2f / abs(previousProjection.m00())).coerceAtLeast(1f)
        val logicalHeight = (2f / abs(previousProjection.m11())).coerceAtLeast(1f)
        val scale = min(rect.width, rect.height) * zoom
        val projectionDepth = maxOf(MinimumOrthographicDepth, scale)
        RenderSystem.setProjectionMatrix(
            Matrix4f().setOrtho(
                0f,
                logicalWidth,
                logicalHeight,
                0f,
                -projectionDepth,
                projectionDepth,
            ),
            VertexSorting.ORTHOGRAPHIC_Z,
        )

        GL33.glDepthFunc(GL33.GL_LEQUAL)
        val centerX = rect.x + rect.width / 2f
        val centerY = rect.y + rect.height / 2f
        stack.translate(centerX + offsetX * zoom, centerY + offsetY * zoom, 0f)
        stack.scale(scale, -scale, scale)
        stack.mulPose(Quaternionf().rotateX(pitch * Mth.DEG_TO_RAD))
        stack.mulPose(Quaternionf().rotateY(yaw * Mth.DEG_TO_RAD))
        stack.translate(-0.5f, -0.5f, -0.5f)

        try {
            RenderSystem.setShaderLights(CUSTOM_IMGUI_LIGHT_0, CUSTOM_IMGUI_LIGHT_1)
            val minecraft = Minecraft.getInstance()
            val buffers = minecraft.renderBuffers().bufferSource()
            val consumer = buffers.getBuffer(Sheets.cutoutBlockSheet())
            minecraft.blockRenderer.modelRenderer.renderModel(
                stack.last(),
                consumer,
                null,
                model,
                1f,
                1f,
                1f,
                LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY,
            )
            buffers.endBatch()
        } finally {
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting)
        }
    }
}

private class VanillaModelBaker(
    private val resourceManager: ResourceManager,
) : ModelBaker {
    private val unbakedModels = mutableMapOf<ResourceLocation, UnbakedModel>()
    private val bakedModels = mutableMapOf<ResourceLocation, BakedModel>()
    private val itemModelGenerator = ItemModelGenerator()
    private val spriteGetter = Function<Material, TextureAtlasSprite> { material ->
        Minecraft.getInstance().modelManager.getAtlas(material.atlasLocation()).getSprite(material.texture())
    }

    fun bakeResource(resource: ResourceLocation): BakedModel {
        require(resource.path.startsWith("models/") && resource.path.endsWith(".json")) {
            VanillaModelLang.NOT_JSON_MODEL.lang(resource)
        }
        val modelId = ResourceLocation.fromNamespaceAndPath(
            resource.namespace,
            resource.path.removePrefix("models/").removeSuffix(".json"),
        )
        getModel(modelId).resolveParents(::getModel)
        return requireNotNull(bake(modelId, BlockModelRotation.X0_Y0)) {
            VanillaModelLang.LOAD_FAILED.lang(resource)
        }
    }

    override fun getModel(location: ResourceLocation): UnbakedModel = unbakedModels.getOrPut(location) {
        when (location.path) {
            "builtin/generated" -> ModelBakery.GENERATION_MARKER
            "builtin/entity" -> ModelBakery.BLOCK_ENTITY_MARKER
            "builtin/missing" -> missingModel()
            else -> loadModel(location)
        }
    }

    override fun bake(location: ResourceLocation, state: ModelState): BakedModel = bakedModels.getOrPut(location) {
        val model = getModel(location)
        model.resolveParents(::getModel)
        if (model is BlockModel && model.rootModel === ModelBakery.GENERATION_MARKER) {
            itemModelGenerator.generateBlockModel(spriteGetter, model)
                .bake(this, model, spriteGetter, state, false)
        } else {
            requireNotNull(model.bake(this, spriteGetter, state)) {
                VanillaModelLang.EMPTY_BAKED_MODEL.lang(location)
            }
        }
    }

    private fun loadModel(location: ResourceLocation): BlockModel {
        val resource = ResourceLocation.fromNamespaceAndPath(location.namespace, "models/${location.path}.json")
        val source = resourceManager.getResource(resource).orElseThrow {
            IllegalArgumentException(VanillaModelLang.MISSING_MODEL.lang(resource))
        }
        return source.openAsReader().use(BlockModel::fromStream).also { it.name = location.toString() }
    }

    private fun missingModel(): BlockModel = BlockModel.fromString(ModelBakery.MISSING_MODEL_MESH).also {
        it.name = ModelBakery.MISSING_MODEL_LOCATION.toString()
    }
}

private fun String.toAssetResourceLocation(): ResourceLocation {
    val relative = substringAfter("assets/", missingDelimiterValue = "")
    require(relative.isNotEmpty() && '/' in relative) { VanillaModelLang.INVALID_ASSET_PATH.lang(this) }
    return ResourceLocation.fromNamespaceAndPath(relative.substringBefore('/'), relative.substringAfter('/'))
}

private fun UiCanvasDrawScope.drawVanillaModelGrid(state: VanillaModelViewerState) {
    val spacing = (GridSpacing * state.zoom).coerceIn(8f, 512f)
    val paint = UiPaint.Color(GridColor)
    var x = (state.offsetX * state.zoom + size.width / 2f) % spacing
    if (x < 0f) x += spacing
    while (x < size.width) {
        drawRect(UiRect(x, 0f, 1f, size.height), paint)
        x += spacing
    }
    var y = (state.offsetY * state.zoom + size.height / 2f) % spacing
    if (y < 0f) y += spacing
    while (y < size.height) {
        drawRect(UiRect(0f, y, size.width, 1f), paint)
        y += spacing
    }
}

private const val GridSpacing = 36f
private const val AutoRotateDegreesPerSecond = 20f
private const val MinimumOrthographicDepth = 1000f
private val GridColor = UiColor(0.62f, 0.7f, 0.85f, 0.14f)

private object VanillaModelLang {
    private const val ROOT = "hollowengine.gui.ide.vanilla_model."

    const val REFERENCE_GRID = ROOT + "reference_grid"
    const val AUTO_ROTATE = ROOT + "auto_rotate"
    const val RELOAD = ROOT + "reload"
    const val BUILTIN_RENDERER = ROOT + "error.builtin_renderer"
    const val LOAD_FAILED = ROOT + "error.load_failed"
    const val NOT_JSON_MODEL = ROOT + "error.not_json_model"
    const val EMPTY_BAKED_MODEL = ROOT + "error.empty_baked_model"
    const val MISSING_MODEL = ROOT + "error.missing_model"
    const val INVALID_ASSET_PATH = ROOT + "error.invalid_asset_path"
}
