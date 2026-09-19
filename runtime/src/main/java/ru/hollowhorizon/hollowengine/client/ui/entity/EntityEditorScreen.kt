package ru.hollowhorizon.hollowengine.client.ui.entity

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.entity.LivingEntity
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.slots.SlotTooltips
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.screen.HollowComposeUiScreen
import ru.hollowhorizon.hollowengine.client.ui.style.CompiledHss
import ru.hollowhorizon.hollowengine.client.ui.style.MinecraftHssResourceLoader
import ru.hollowhorizon.hollowengine.client.ui.widgets.tooltipOnHover

private const val Stylesheet = "hollowengine:ui/styles/entity-editor.hss"
private const val WidgetStylesheet = "hollowengine:ui/styles/widgets.hss"
internal const val EntityEditorSearchInput = "ee-search-input"

private const val SidebarMinWidth = 240f
private const val SidebarMaxWidth = 520f


private fun editorStylesheet(): CompiledHss {
    val sheets = listOf(WidgetStylesheet, Stylesheet).map(MinecraftHssResourceLoader::load)
    var offset = 0
    return CompiledHss(
        rules = sheets.flatMap { sheet ->
            val shifted = sheet.rules.map { it.copy(order = it.order + offset) }
            offset += (sheet.rules.maxOfOrNull { it.order } ?: -1) + 1
            shifted
        },
        keyframes = buildMap { sheets.forEach { putAll(it.keyframes) } },
    )
}

internal class EntityEditorScreen(
    val session: EntityEditorSession,
) : HollowComposeUiScreen(EntityEditorLang.title, editorStylesheet()) {

    @Composable
    override fun Content() {
        CompositionLocalProvider(LocalEntityEditorSession provides session) {
            var sidebarWidth by remember { mutableStateOf(320f) }

            Row(tags = listOf("ee-root"), modifier = Modifier.size(100.percent, 100.percent)) {
                EntityViewport(session)
                SidebarSplitter(sidebarWidth) { sidebarWidth = it }
                EntitySidebar(session, sidebarWidth)
            }

            session.pendingPicker?.let { picker -> AssetPickerDialog(picker) }
            if (session.slotSessionId != null) InventoryDialog(session)
        }
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        val control = modifiers and GLFW.GLFW_MOD_CONTROL != 0
        if (control && keyCode == GLFW.GLFW_KEY_F) {
            session.searchOpen = true
            Minecraft.getInstance().execute { focusInput(EntityEditorSearchInput) }
            return true
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (session.pendingPicker != null) {
                session.pendingPicker = null
                return true
            }
            if (session.slotSessionId != null) {
                session.closeSlots()
                return true
            }
            if (session.searchOpen && session.query.isNotEmpty()) {
                session.query = ""
                return true
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun removed() {
        session.closeSlots()
        super.removed()
    }

    override fun renderAfterUi(graphics: GuiGraphics, mouseX: Int, mouseY: Int) {
        if (session.slotSessionId != null) SlotTooltips.render(graphics, mouseX, mouseY)
    }

    override fun isPauseScreen(): Boolean = false
}

@Composable
private fun SidebarSplitter(width: Float, onWidthChange: (Float) -> Unit) {
    val dragStart = remember { floatArrayOf(width) }
    Box(
        tags = listOf("ee-splitter"),
        modifier = Modifier.size(5.px, 100.percent)
            .input(hoverable = true, draggable = true)
            .cursor(UiCursorShape.RESIZE_HORIZONTAL)
            .onPress { dragStart[0] = width }
            .onDrag { event ->
                onWidthChange((dragStart[0] - event.dragTotalX).coerceIn(SidebarMinWidth, SidebarMaxWidth))
                event.consume()
            },
    )
}

@Composable
private fun EntityViewport(session: EntityEditorSession) {
    val entity = session.entity
    val view = session.view

    LaunchedEffect(session) {
        while (true) {
            withFrameNanos { }
            if (session.autoRotate) {
                session.view = session.view.copy(yaw = (session.view.yaw + 0.35f) % 360f)
            }
        }
    }

    Box(
        tags = listOf("ee-viewport"),
        modifier = Modifier.input(hoverable = true, draggable = true)
            .onDrag { event ->
                session.autoRotate = false
                if (event.button == 1) {
                    session.view = session.view.copy(
                        offsetX = session.view.offsetX + event.deltaX,
                        offsetY = session.view.offsetY + event.deltaY,
                    )
                } else {
                    session.view = session.view.copy(
                        yaw = (session.view.yaw - event.deltaX / 2f) % 360f,
                        pitch = (session.view.pitch - event.deltaY / 2f).coerceIn(-75f, 75f),
                    )
                }
                event.consume()
            }
            .onScroll { event ->
                val factor = if (event.scrollY > 0f) 0.9f else 1.1f
                session.view = session.view.copy(zoom = (session.view.zoom * factor).coerceIn(0.2f, 8f))
                event.consume()
            },
    ) {
        if (entity == null) {
            Text(EntityEditorLang.noPreview, tags = listOf("ee-hint"))
        } else {
            Entity(entity, view = view, modifier = Modifier.size(100.percent, 100.percent))
        }

        Column(tags = listOf("ee-viewport-title")) {
            Text(session.snapshot.title, tags = listOf("ee-title"))
            Text(session.snapshot.typeId, tags = listOf("ee-subtitle"))
        }
        ViewportToolbar(session)
        ViewportStats(session)
    }
}

@Composable
private fun ViewportToolbar(session: EntityEditorSession) {
    Column(tags = listOf("ee-toolbar")) {
        ToolbarToggle(EntityEditorIcons.RELOAD, EntityEditorLang.autoRotate, session.autoRotate) {
            session.autoRotate = !session.autoRotate
        }
        ToolbarToggle(EntityEditorIcons.ZOOM_RESET, EntityEditorLang.resetView, false) {
            session.autoRotate = false
            session.view = UiEntityView.Portrait
        }
    }
}

@Composable
private fun ToolbarToggle(icon: String, tooltip: String, active: Boolean, onToggle: () -> Unit) {
    Box(
        tags = if (active) listOf("ee-chip", "selected") else listOf("ee-chip"),
        modifier = Modifier.input(hoverable = true, clickable = true)
            .cursor(UiCursorShape.HAND)
            .tooltipOnHover(tooltip)
            .onClick { onToggle() },
    ) {
        Image(icon, tags = listOf("ee-chip-icon"))
    }
}

@Composable
private fun ViewportStats(session: EntityEditorSession) {
    val entity = session.entity
    Column(tags = listOf("ee-stats")) {
        StatRow(EntityEditorLang.statComponents, session.entries.size.toString())
        StatRow(EntityEditorLang.statScripts, session.attachedScripts.size.toString())
        (entity as? LivingEntity)?.let { living ->
            StatRow(
                EntityEditorLang.statHealth,
                "${formatNumber(living.health.toDouble(), false)} / ${formatNumber(living.maxHealth.toDouble(), false)}",
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(tags = listOf("ee-stat-row")) {
        Text(label, tags = listOf("ee-stat-label"), modifier = Modifier.grow(1f))
        Text(value, tags = listOf("ee-stat-value"))
    }
}
