package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.*
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import org.lwjgl.glfw.GLFW
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import ru.hollowhorizon.hollowengine.client.editor.TransformGizmoEditor
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.docking.*
import ru.hollowhorizon.hollowengine.client.ui.ide.asset.*
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeImageEditor
import ru.hollowhorizon.hollowengine.client.ui.ide.files.animator.HollowIdeAnimatorEditor
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.RigEditorPanel
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeSoundsEditor
import ru.hollowhorizon.hollowengine.client.ui.ide.panels.HollowIdeConsolePanel
import ru.hollowhorizon.hollowengine.client.ui.ide.panels.HollowIdeUiProfilerPanel
import ru.hollowhorizon.hollowengine.client.ui.ide.panels.ModelEditorPanel
import ru.hollowhorizon.hollowengine.client.ui.ide.panels.VanillaModelEditorPanel
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene.CutsceneEditorSessions
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.CutscenePropertiesDock
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.CutsceneTimelineDock
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.ui.CutsceneViewportDock
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect
import ru.hollowhorizon.hollowengine.client.ui.render.MinecraftUiRenderer
import ru.hollowhorizon.hollowengine.client.ui.render.UiRenderTarget
import ru.hollowhorizon.hollowengine.client.ui.style.UiImageFit
import ru.hollowhorizon.hollowengine.client.ui.style.parseColor
import ru.hollowhorizon.hollowengine.client.ui.widgets.*
import ru.hollowhorizon.hollowengine.common.scripting.ide.ui.hssColorLiteralText
import ru.hollowhorizon.hollowengine.client.utils.IconHelper
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.config.EditMode
import ru.hollowhorizon.hollowengine.common.config.HollowEngineConfig
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderTickEvent
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager.fromReadablePath
import ru.hollowhorizon.hollowengine.common.utils.DesktopUtil
import ru.hollowhorizon.hollowengine.common.scripting.ide.DefinitionLocation
import ru.hollowhorizon.hollowengine.common.scripting.ide.InlayAction
import ru.hollowhorizon.hollowengine.common.scripting.ide.ResourceLocationTargets
import java.io.File

/** A file dragged out of the project tree; anything that accepts drops can look for this payload. */
data class HollowIdeFileDrag(val path: String, val isDirectory: Boolean = false)

/**
 * How a project path is written inside a script: `assets/<namespace>/...` and `data/<namespace>/...` are
 * resource locations, everything else stays the path it already was.
 */
internal fun projectPathReference(path: String): String {
    val segments = path.split('/')
    if (segments.size < 3) return path
    if (segments[0] != "assets" && segments[0] != "data") return path
    return "${segments[1]}:${segments.drop(2).joinToString("/")}"
}

internal const val ProjectTreeId = "ide-project-tree"
internal const val AssetManagerId = "ide-asset-manager"

internal const val ProjectFilterInputId = "ide-project-filter"
internal const val ConsoleId = "ide-console"
internal const val CutsceneTimelineId = "ide-cutscene-timeline"
internal const val CutscenePropertiesId = "ide-cutscene-properties"
internal const val CutsceneViewportId = "ide-cutscene-viewport"
internal const val UiProfilerId = "ide-ui-profiler"
internal const val LogoIcon = "hollowengine:textures/gui/logo/logo.svg"
internal const val ProjectIcon = "hollowengine:textures/gui/icons/folder.svg"
internal const val AssetManagerIcon = "hollowengine:textures/gui/icons/folder_assets.svg"
internal const val ConsoleIcon = "hollowengine:textures/gui/icons/console.svg"
internal const val SearchIcon = "hollowengine:textures/gui/icons/search.svg"
internal const val CutsceneIcon = "hollowengine:textures/gui/icons/film.svg"

@ClientOnly
object HollowIdeOverlay {
    var useHollowUiOverlay: Boolean = true

    private val fileTypes = HollowIdeFileTypeRegistry().apply {
        registerBuiltinFileTypes(
            modelEditor = { file -> ModelEditorPanel(file.path) },
            imageEditor = { file -> HollowIdeImageEditor(file, file::save) },
            videoEditor = { file ->
                Video(
                    source = file.path,
                    fit = UiImageFit.CONTAIN,
                    modifier = Modifier.size(100.percent, 100.percent),
                )
            },
            soundsEditor = { file -> HollowIdeSoundsEditor(file) },
            animatorEditor = { file -> HollowIdeAnimatorEditor(file) },
            rigEditor = { file -> RigEditorPanel(file) },
            textEditor = { file -> FileEditor(file) },
        )
        registerAssetFileTypes(
            imageEditor = { file -> HollowIdeImageEditor(file, file::save) },
            textEditor = { file -> FileEditor(file) },
            jsonModelEditor = { file -> VanillaModelEditorPanel(file.path) },
        )
    }
    private val model = HollowIdeModel(fileTypes)
    private val assetManagerState = AssetManagerState()
    private val dock = DockingState()
    private val surface = HollowUiSurface()
    private val renderer = MinecraftUiRenderer()
    private val pipeline = PipelinedUiFrameBuilder()

    private const val PIPELINE_FRAMES = true
    private var initialized = false
    private var activeButton: Int? = null
    private var collapsed by mutableStateOf(true)
    private var hideToolbarConfirmationVisible by mutableStateOf(false)
    private val projectFilter = UiTreeFilterState(ProjectFilterInputId)
    private var openDropdown by mutableStateOf<String?>(null)
    private var statusText by mutableStateOf("")
    private val project = HollowIdeProjectController(
        model = model,
        focusProjectTree = {
            dock.focus(ProjectTreeId)
            if (surface.runtime.focusedKey != ProjectFilterInputId) surface.runtime.unfocus()
        },
        shortcutsActive = {
            dock.focusedItemId == ProjectTreeId &&
                    surface.runtime.focusedKey != ProjectFilterInputId
        },
        closeDockItem = { dock.close(it) },
        openFile = { openFileDockItem(it) },
        setStatus = { statusText = it },
        pointerX = { surface.runtime.mouseX },
        pointerY = { surface.runtime.mouseY },
    )
    private val diagnosticsPanels = mutableStateMapOf<String, Boolean>()
    private val diagnosticsPanelHeights = mutableStateMapOf<String, Float>()
    private var editorAnalysisRevision by mutableStateOf(0)
    private val editorSessions = mutableMapOf<String, HollowIdeEditorSession>()
    private val editorStates = mutableMapOf<String, TextFieldState>()
    private var fileContextMenu by mutableStateOf<FileContextMenu?>(null)
    private val dragAndDrop = UiDragAndDropState()
    private var refreshFilesRequested = false
    private val externalFiles = HollowIdeExternalFiles(
        onFocusGained = { refreshFilesRequested = true },
        onMove = { files, x, y -> updateExternalDrag(files, x, y) },
        onLeave = {
            pipeline.await()
            if (dragAndDrop.item?.payload is HollowIdeExternalFileDrag) finishFileDrag()
        },
        onDrop = { files, x, y ->
            try {
                updateExternalDrag(files, x, y) && dragAndDrop.drop()
            } finally {
                finishFileDrag()
            }
        },
        onFocusLost = {
            if (dragAndDrop.item?.externalFiles?.isNotEmpty() == true) {
                pipeline.await()
                exportFileDrag()
            }
        },
    )
    private val findStates = mutableStateMapOf<String, HollowIdeFindState>()
    private val search = HollowIdeSearchController { statusText = it }
    private var colorPicker by mutableStateOf<EditorColorPicker?>(null)

    private fun editorState(file: HollowIdeOpenFile): TextFieldState = editorStates.getOrPut(file.path) {
        TextFieldState(
            initialText = file.text,
            multiline = true,
            pasteTransformer = KotlinStringPasteTransformer.takeIf { file.path.isKotlinSource() },
        )
    }

    private fun editorSession(path: String): HollowIdeEditorSession = editorSessions.getOrPut(path) {
        HollowIdeEditorSession(path) {
            editorAnalysisRevision++
        }
    }

    private var lastMouseX = 0f
    private var lastMouseY = 0f

    init {
        initialize()
    }

    /** Registers a new IDE file type. IDs must be unique; higher priorities are matched first. */
    fun registerFileType(type: HollowIdeFileType) {
        fileTypes.register(type)
    }

    fun isVisible(): Boolean = useHollowUiOverlay && isAvailable()

    fun isMouseOver(x: Float, y: Float): Boolean {
        if (!isVisible()) return false
        val point = hollowIdeOverlayPoint(x, y)
        return surface.runtime.lastFrame?.hitsVisible(point.x, point.y) ?: false
    }

    fun hasFocusedInput(): Boolean = isVisible() && surface.runtime.isAnyFocused

    fun openPath(path: String): Boolean {
        val result = model.openFile(path)
        if (result !is HollowIdeOpenResult.File) return false
        collapsed = false
        openFileDockItem(result.file)
        return true
    }

    /** Opens [path], starting it from [initial] when there is no such file yet. */
    fun openOrCreate(path: String, initial: () -> ByteArray): Boolean =
        model.createIfMissing(path, initial()) && openPath(path)

    /** While Windows owns the gesture the IDE must not act on the input it keeps receiving. */
    private val nativeFileDragActive: Boolean
        get() = WindowsFileDragSource.active || externalFiles.active

    fun handleMouseMove(x: Float, y: Float): Boolean {
        if (nativeFileDragActive) return true
        if (!isVisible()) return false
        pipeline.await()
        val point = hollowIdeOverlayPoint(x, y)
        val deltaX = point.x - lastMouseX
        val deltaY = point.y - lastMouseY
        lastMouseX = point.x
        lastMouseY = point.y
        val button = activeButton ?: return false
        val handled = surface.runtime.mouseDragged(point.x, point.y, button, deltaX, deltaY, currentUiKeyModifiers())
        if (point.x < 0f || point.y < 0f ||
            point.x >= HollowIdeScale.scaledWidth() || point.y >= HollowIdeScale.scaledHeight()) {
            exportFileDrag()
        }
        return handled
    }

    private fun exportFileDrag() {
        val files = dragAndDrop.item?.externalFiles.orEmpty()
        if (files.isEmpty()) return
        if (activeButton == null) return
        if (WindowsFileDragSource.request(files, ::finishFileDrag)) finishFileDrag()
    }

    private fun finishFileDrag() {
        activeButton = null
        dragAndDrop.cancel()
        surface.runtime.cancelPointerInput()
    }

    private fun updateExternalDrag(files: List<File>, x: Float, y: Float): Boolean {
        if (!isVisible() || collapsed) return false
        pipeline.await()
        lastMouseX = x
        lastMouseY = y
        val current = dragAndDrop.item?.payload as? HollowIdeExternalFileDrag
        if (current?.files != files) {
            dragAndDrop.begin(UiDragItem(HollowIdeExternalFileDrag(files)), x, y)
        } else dragAndDrop.move(x, y)
        return dragAndDrop.canDrop
    }

    fun handleMouseButton(x: Float, y: Float, button: Int, action: Int): Boolean {
        if (nativeFileDragActive) return true
        if (!isVisible()) return false
        pipeline.await()
        val point = hollowIdeOverlayPoint(x, y)

        return when (action) {
            GLFW.GLFW_PRESS -> {
                focusDockContentAt(point.x, point.y)
                val result = surface.runtime.mouseClicked(point.x, point.y, button, currentUiKeyModifiers())
                if (result) activeButton = button
                result
            }

            GLFW.GLFW_RELEASE -> {
                activeButton = null
                surface.runtime.mouseReleased(point.x, point.y, button, currentUiKeyModifiers())
            }

            else -> false
        }
    }

    fun handleMouseScroll(x: Float, y: Float, scrollX: Double, scrollY: Double): Boolean {
        if (nativeFileDragActive) return true
        if (!isVisible()) return false
        pipeline.await()
        val point = hollowIdeOverlayPoint(x, y)
        return surface.runtime.mouseScrolled(
            point.x,
            point.y,
            scrollX.toFloat(),
            scrollY.toFloat(),
            currentUiKeyModifiers(),
        )
    }

    fun handleKey(key: Int, scanCode: Int, action: Int, modifiers: Int): Boolean {
        if (nativeFileDragActive) return true
        if (!isVisible() || collapsed) return false
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) return false
        pipeline.await()
        return surface.runtime.keyPressed(key, scanCode, modifiers, repeat = action == GLFW.GLFW_REPEAT)
    }

    private fun focusDockContentAt(x: Float, y: Float) {
        var node = surface.runtime.lastFrame?.hitTest(x, y)?.node ?: return
        while (true) {
            val id = node.id
            if (id != null && id.endsWith("-content") && dock.focusContent(id)) return
            node = node.layoutState.parentNode ?: return
        }
    }

    fun handleChar(codePoint: Int, modifiers: Int): Boolean {
        if (nativeFileDragActive) return true
        if (!isVisible() || collapsed) return false
        pipeline.await()
        return surface.runtime.charTyped(codePoint.toChar(), modifiers)
    }

    @SubscribeEvent
    fun render(event: RenderTickEvent.Blit) {
        if (WindowsFileDragSource.active) {
            pipeline.await()
            WindowsFileDragSource.runPending(event.minecraft.window.window)
        }
        externalFiles.update(event.minecraft.window.window, isVisible() && !collapsed)
        if (!isVisible()) return
        if (refreshFilesRequested && !externalFiles.active) {
            pipeline.await()
            refreshFilesRequested = false
            model.refreshExternalFiles()
        }
        AssetManagerLifecycle.observe(event.minecraft)
        renderOverlay(currentBlitTarget())
    }

    private fun initialize() {
        if (initialized) return
        initialized = true
        dock.onTabContextMenu = ::openFileContextMenu
        model.onFileRemoved = ::forgetFile
        dock.open(DockItem(ProjectTreeId, "hollowengine.gui.ide.project_tree".lang, ProjectIcon))
        surface.setContent { Content() }
    }
    
    private fun insertFileReference(
        file: HollowIdeOpenFile,
        editor: TextFieldState,
        droppedPath: String,
        x: Float,
        y: Float,
    ): Boolean {
        if (file.readOnly) return false
        val offset = editor.offsetAtPoint?.invoke(x, y) ?: editor.caret
        val reference = projectPathReference(droppedPath)
        val text = editor.text
        val at = offset.coerceIn(0, text.length)
        val changed = editor.applyEdit(
            text.substring(0, at) + reference + text.substring(at),
            listOf(UiTextCaret(at + reference.length)),
        )
        if (changed) {
            model.updateText(file.path, editor.text)
            dock.updateItem(file.dockItem())
            statusText = "Inserted $reference"
        }
        surface.runtime.focus("editor-${file.id}")
        return changed
    }

    private fun forgetFile(path: String) {
        val id = fileDockItemId(path)
        dock.close(id)
        editorSessions.remove(path)?.close()
        editorStates.remove(path)
        diagnosticsPanels.remove(id)
        diagnosticsPanelHeights.remove(id)
        findStates.remove(path)
        if (colorPicker?.path == path) colorPicker = null
        if (fileContextMenu?.path == path) fileContextMenu = null
    }

    /** Right-click on a tab: builds the menu the file's type declares for it. */
    private fun openFileContextMenu(item: DockItem, event: UiEvent) {
        val file = model.files.values.firstOrNull { it.id == item.id }
        if (file == null) {
            fileContextMenu = null
            return
        }
        val context = fileActionContext(file)
        fileContextMenu = FileContextMenu(
            path = file.path,
            x = event.x,
            y = event.y,
            actions = fileContextMenuActions(context).map { action ->
                FileContextMenuEntry(action, enabled = action.isEnabled(context))
            },
        )
    }

    private fun closeFileContextMenu(): Boolean {
        if (fileContextMenu == null) return false
        fileContextMenu = null
        return true
    }

    private fun runFileAction(action: HollowIdeFileAction) {
        val path = fileContextMenu?.path
        fileContextMenu = null
        val file = path?.let { model.files[it] } ?: return
        action.run(fileActionContext(file))
    }

    private fun fileActionContext(target: HollowIdeOpenFile): HollowIdeFileActionContext =
        object : HollowIdeFileActionContext {
            override val file: HollowIdeOpenFile = target

            override val canFormat: Boolean
                get() = !target.readOnly && target.textOrNull != null && editorSession(target.path).canFormat

            override fun save(): Boolean {
                val saved = model.save(target.path)
                dock.updateItem(target.dockItem())
                return saved
            }

            override fun close() {
                dock.close(target.id)
            }

            override fun closeOthers() {
                model.files.values.filter { it.id != target.id }.forEach { dock.close(it.id) }
            }

            override fun closeAll() {
                model.files.values.forEach { dock.close(it.id) }
            }

            override fun reformat() {
                formatFile(target)
            }

            override fun revealInProjectView() {
                model.revealPath(target.path)
                dock.focus(ProjectTreeId)
            }

            override fun showInExplorer() {
                DesktopUtil.openInExplorer(target.path.fromReadablePath())
            }

            override fun copyPath() {
                Minecraft.getInstance().keyboardHandler.clipboard = target.path
                statusText = "Copied ${target.path}"
            }

            override fun setStatus(message: String) {
                statusText = message
            }
        }

    @Composable
    private fun Content() {
        Box(
            id = "ide-root",
            modifier = Modifier.style("hollowengine:ui/styles/ide.hss")
                .style("hollowengine:ui/styles/widgets.hss")
                .size(100.percent, 100.percent)
                .focusScope()
                .onKeyInput { input ->
                    val handled = !input.repeat && (
                            input.key == GLFW.GLFW_KEY_ESCAPE && closeFileContextMenu() ||
                                    handleHollowIdeSearchKey(search, input.key, input.modifiers, ::openSearchResult) ||
                                    project.handleNameDialogKey(input.key) ||
                                    handleSearchOverlayShortcut(input.key, input.modifiers) ||
                                    handleProjectFilterShortcut(input.key, input.modifiers) ||
                                    project.handleShortcut(input.key, input.modifiers) ||
                                    handleDockShortcut(input.key, input.modifiers) ||
                                    handleEditorShortcut(input.key, input.modifiers) ||
                                    input.key == GLFW.GLFW_KEY_F4 && goToDefinition()
                            )
                    if (handled) input.consume()
                }
        ) {
            CompositionLocalProvider(LocalDragAndDrop provides dragAndDrop) {
                if (collapsed) {
                    GearButton()
                } else {
                    Column(modifier = Modifier.size(100.percent, 100.percent)) {
                        Toolbar()
                        DockSpace(
                            state = dock,
                            id = "ide-dock",
                            modifier = Modifier.size(100.percent, 0.px)
                                .grow(1f),
                            content = { item -> DockContent(item) },
                        )
                    }
                    HollowIdeFileContextMenu(
                        menu = fileContextMenu,
                        onAction = ::runFileAction,
                        onDismiss = { fileContextMenu = null },
                    )
                    HollowIdeSearchDialog(search, ::openSearchResult)
                    EditorColorPickerPopup()
                    UiDragGhost(dragAndDrop)
                }
                HollowIdeHideToolbarDialog(
                    visible = hideToolbarConfirmationVisible,
                    onConfirm = ::hideToolbar,
                    onCancel = { hideToolbarConfirmationVisible = false },
                )
            }
        }
    }

    @Composable
    private fun GearButton() {
        var popup by remember { mutableStateOf(false) }
        var anchorBounds by remember { mutableStateOf(UiRect.Zero) }
        Box(
            id = "ide-logo",
            modifier = Modifier.cursor(UiCursorShape.HAND)
                .onClick { event ->
                    if (event.isLeftClick()) {
                        collapsed = !collapsed
                        openDropdown = null
                        event.consume()
                    } else if (event.isRightClick()) {
                        popup = true
                    }
                }
                .onPlaced {
                    anchorBounds = it
                }
        ) {
            Image(LogoIcon, tags = listOf("ide-logo-icon"))
        }
        if (popup) {
            ContextMenu(
                "ide-editor-menu", anchorBounds, listOf(
                    UiDropdownItem("Show always") {
                        HollowEngineConfig.editMode = EditMode.ENABLED
                        if (collapsed) {
                            collapsed = false
                        }
                    },
                    UiDropdownItem("Collapse") {
                        if (!collapsed) {
                            collapsed = true
                        }
                    },
                    UiDropdownItem("Hide") {
                        requestToolbarHide()
                    },
                    UiDropdownItem("Show only in chat menu") {
                        HollowEngineConfig.editMode = EditMode.CHAT_ONLY
                    }
                )) { popup = it }
        }
    }

    private fun requestToolbarHide() {
        if (HollowEngineConfig.showToolbarHideConfirmation) {
            hideToolbarConfirmationVisible = true
        } else {
            hideToolbar(false)
        }
    }

    private fun hideToolbar(doNotShowAgain: Boolean) {
        hideToolbarConfirmationVisible = false
        if (doNotShowAgain) HollowEngineConfig.showToolbarHideConfirmation = false
        HollowEngineConfig.editMode = EditMode.DISABLED
    }

    @Composable
    private fun Toolbar() {
        Row(
            id = "ide-toolbar",
            modifier = Modifier.alignItems(vertical = UiAlign.CENTER),
        ) {
            GearButton()
            ToolbarMenus()
            Box(modifier = Modifier.size(0.px, 100.percent).grow(1f))
            Text(statusText, tags = listOf("ide-status"))
        }
    }

    @Composable
    private fun ToolbarMenus() {
        UiDropdown(
            id = "ide-file-menu",
            label = "hollowengine.gui.ide.file".lang,
            expanded = openDropdown == "file",
            onExpandedChange = { openDropdown = if (it) "file" else null },
            items = hollowIdeFileMenuItems(
                model = model,
                dock = dock,
                focusedFile = ::focusedFile,
                canReformat = { file -> fileActionContext(file).canFormat },
                onReformat = ::formatFile,
            ),
        )
        UiDropdown(
            id = "ide-windows-menu",
            label = "hollowengine.gui.ide.windows".lang,
            expanded = openDropdown == "windows",
            onExpandedChange = { openDropdown = if (it) "windows" else null },
            items = hollowIdeWindowMenuItems(model, dock),
        )
        UiDropdown(
            id = "ide-tools-menu",
            label = "hollowengine.gui.ide.tools".lang,
            expanded = openDropdown == "tools",
            onExpandedChange = { openDropdown = if (it) "tools" else null },
            items = hollowIdeToolMenuItems(dock, surface.runtime.profiler),
        )
        UiDropdown(
            id = "ide-help-menu",
            label = "hollowengine.gui.ide.help".lang,
            expanded = openDropdown == "help",
            onExpandedChange = { openDropdown = if (it) "help" else null },
            items = hollowIdeHelpMenuItems(),
        )
    }

    @Composable
    private fun DockContent(item: DockItem) {
        when (item.id) {
            ProjectTreeId -> ProjectTree()
            AssetManagerId -> AssetManagerPanel(
                state = assetManagerState,
                onOpenFile = ::openAssetFile,
                onOverrideFile = ::overrideAssetFile,
                onHideFile = ::hideAssetFile,
                onRestoreFile = ::restoreAssetFile,
                onFocusFilter = ::requestSurfaceFocus,
            )
            ConsoleId -> HollowIdeConsolePanel()
            CutsceneTimelineId -> CutsceneTimelineDock(
                session = CutsceneEditorSessions.default,
                keyboardActive = dock.focusedItemId == CutsceneTimelineId,
            )

            CutscenePropertiesId -> CutscenePropertiesDock(CutsceneEditorSessions.default)
            CutsceneViewportId -> CutsceneViewportDock()
            UiProfilerId -> HollowIdeUiProfilerPanel(surface.runtime.profiler)
            else -> model.files.values.firstOrNull { it.id == item.id }?.let { file ->
                file.type.editor(file)
                LaunchedEffect(file.dirty) {
                    dock.updateItem(file.dockItem())
                }
            } ?: EmptyEditor()
        }
    }

    @Composable
    private fun ProjectTree() {
        val rootDrop = "ide-project-root-drop"
        val rootHighlighted = dragAndDrop.hoveredTargetId == rootDrop && dragAndDrop.canDrop
        Column(
            tags = listOfNotNull("ide-panel", "project-tree-panel", "drop-target".takeIf { rootHighlighted }),
            modifier = Modifier.size(100.percent, 100.percent)
                .dropTarget(
                    id = rootDrop,
                    accepts = { (it.payload as? HollowIdeExternalFileDrag)?.canImportInto("".fromReadablePath()) == true },
                    onDrop = { item, _, _ ->
                        val files = item.payload as? HollowIdeExternalFileDrag
                        files != null && project.importFiles(files.files, "")
                    },
                ),
        ) {
            UiTreeView(
                items = model.visibleTreeItems(projectFilter.query),
                onToggle = project::toggle,
                onSelect = project::select,
                filterState = projectFilter,
                filterPlaceholder = "hollowengine.message.filter".lang,
                onFilterOpened = ::requestSurfaceFocus,
                dragItem = { item ->
                    val node = item.payload
                    node.takeIf { it.path.isNotEmpty() }?.let {
                        UiDragItem(
                            payload = HollowIdeFileDrag(node.path, node.isDirectory),
                            icon = IconHelper.forPath(node.path, node.isDirectory).toString(),
                            label = node.name,
                            externalFiles = model.exportFiles(model.selectedOr(node.path)),
                        )
                    }
                },
                onDrop = { item, dragged ->
                    when (val file = dragged.payload) {
                        is HollowIdeFileDrag -> project.moveInto(file.path, item.payload.path)
                        is HollowIdeExternalFileDrag -> project.importFiles(file.files, item.payload.dropDirectoryPath)
                        else -> false
                    }
                },
                canDrop = { item, dragged ->
                    when (val payload = dragged.payload) {
                        is HollowIdeExternalFileDrag -> payload.canImportInto(item.payload.dropDirectoryPath.fromReadablePath())
                        is HollowIdeFileDrag -> item.payload.isDirectory && payload.path != item.payload.path &&
                            !item.payload.path.startsWith(payload.path + "/")
                        else -> false
                    }
                },
            )
            HollowIdeProjectContextMenu(
                menu = project.contextMenu,
                onCreateFile = project::openCreateFileDialog,
                onCreateFolder = project::openCreateFolderDialog,
                onCreateScript = project::openCreateScriptDialog,
                onCreateSoundEvents = project::createSoundEvents,
                onRename = project::openRenameDialog,
                onCopy = { project.copy(it, cut = false) },
                onCut = { project.copy(it, cut = true) },
                onPaste = project::pasteInto,
                onShowInExplorer = project::showInExplorer,
                onDelete = project::delete,
                onDismiss = { project.closePopups() },
            )
            val dialog = project.nameDialog
            HollowIdeProjectNameDialog(
                dialog = dialog,
                onNameChange = project::updateNameDialog,
                onConfirm = project::applyNameDialog,
                onCancel = project::cancelNameDialog,
            )
            LaunchedEffect(dialog?.action, dialog?.path) {
                if (dialog == null) return@LaunchedEffect
                Minecraft.getInstance().execute {
                    pipeline.await()
                    surface.runtime.focus(ProjectNameDialogInputId)
                }
            }
        }
    }

    @Composable
    private fun FileEditor(file: HollowIdeOpenFile) {
        val editorSession = remember(file.path) { editorSession(file.path) }
        val editorState = editorState(file)
        val analysisRevision = editorAnalysisRevision.toLong() + editorSession.revision
        val diagnostics = editorSession.diagnostics(file.text)
        LaunchedEffect(file.id, diagnostics.isEmpty()) {
            if (diagnostics.isEmpty()) diagnosticsPanels.remove(file.id)
        }
        val inlayHints = editorSession.inlayHints(file.text)
        val fontSize = HollowIdeFontSize.size
        val editorId = "editor-${file.id}"
        val find = findStates[file.path]
        val matches = find?.matches(file.text).orEmpty()
        Column(tags = listOf("ide-editor-shell"), modifier = Modifier.size(100.percent, 100.percent)) {
            if (find != null) {
                HollowIdeFindBar(
                    state = find,
                    matchCount = matches.size,
                    actions = HollowIdeFindActions(
                        onNavigate = { delta -> navigateFind(file, delta) },
                        onReplaceCurrent = { replaceCurrentMatch(file) },
                        onReplaceAll = { replaceAllMatches(file) },
                        onClose = { closeFind(file) },
                    ),
                )
            }
            Box(
                id = "editor-stack-${file.id}",
                mode = UiBoxMode.STACK,
                tags = listOf("ide-editor-stack"),
                modifier = Modifier.grow(1f)
                    .dropTarget(
                        accepts = { !file.readOnly && it.payload is HollowIdeFileDrag },
                        onDragOver = { _, x, y ->
                            editorState.offsetAtPoint?.invoke(x, y)?.let(editorState::moveCaret)
                        },
                        onDrop = { item, x, y ->
                            val dropped = item.payload as? HollowIdeFileDrag ?: return@dropTarget false
                            insertFileReference(file, editorState, dropped.path, x, y)
                        },
                    ),
            ) {
                UiCodeEditor(
                    value = file.text,
                    onChange = { text ->
                        model.updateText(file.path, text)
                        editorSession.requestAnalysis(text, text.length)
                        dock.updateItem(file.dockItem())
                    },
                    highlighter = editorSession.highlighter,
                    completions = if (file.readOnly) null else editorSession.completions,
                    signatureHelp = editorSession.signatures,
                    hoverInfo = editorSession.hover,
                    diagnostics = diagnostics,
                    searchMatches = matches,
                    activeSearchMatch = find?.let { matches.getOrNull(it.currentIndex) },
                    inlayHints = inlayHints,
                    inlayRevision = analysisRevision,
                    onInlayAction = { action -> runInlayAction(file, action) },
                    readOnly = file.readOnly,
                    fontSize = fontSize,
                    state = editorState,
                    id = editorId,
                    attributes = mapOf("analysis-revision" to analysisRevision.toString()),
                    modifier = Modifier.size(100.percent, 100.percent)
                        .onFocus {
                            dock.focus(file.id)
                        }
                        .onScroll { event ->
                            if (!event.isCtrlDown()) return@onScroll
                            HollowIdeFontSize.zoom(event.rawScrollY)
                            event.consume()
                        }
                )
                HollowIdeDiagnosticsBadge(file.id, diagnostics) { id ->
                    diagnosticsPanels[id] = diagnosticsPanels[id] != true
                }
            }
            if (diagnostics.isNotEmpty() && diagnosticsPanels[file.id] == true) {
                val height = diagnosticsPanelHeights[file.id] ?: DefaultDiagnosticsPanelHeight
                HollowIdeDiagnosticsPanel(file.id, diagnostics, height) { id, requestedHeight ->
                    diagnosticsPanelHeights[id] = requestedHeight.coerceIn(
                        MinDiagnosticsPanelHeight,
                        MaxDiagnosticsPanelHeight,
                    )
                }
            }
        }
    }

    @Composable
    private fun EmptyEditor() {
        Column(tags = listOf("ide-empty-editor")) {
            Text("Open a file from Project Tree", tags = listOf("ide-empty-title"))
            Text(statusText, tags = listOf("ide-status"))
        }
    }

    private fun openFileDockItem(file: HollowIdeOpenFile) {
        statusText = ""
        if (!dock.contains(file.id)) {
            val hadOpenEditor = model.files.values.any { dock.contains(it.id) }
            dock.open(file.dockItem(), editorTarget())
            if (!hadOpenEditor) {
                dock.setSplitFractionForItem(ProjectTreeId, file.id, 0.28f)
            }
        } else {
            dock.updateItem(file.dockItem())
            dock.focus(file.id)
        }
    }

    private fun openAssetFile(
        scope: AssetResourceScope,
        asset: AssetFile,
        remoteBytes: ByteArray?,
        forceText: Boolean,
    ) {
        val manager = assetResourceManager(scope)
        if (manager == null && remoteBytes == null) {
            statusText = AssetManagerLang.SERVER_UNAVAILABLE.lang
            return
        }
        val modelTypeId = assetFileTypeId(scope, asset.location.path, ByteArray(0), forceText)
            ?.takeIf { it == AssetJsonModelFileTypeId || it == "model" }
        val bytes = if (modelTypeId != null) {
            ByteArray(0)
        } else {
            remoteBytes ?: runCatching {
                requireNotNull(manager).getResource(asset.location).orElseThrow().open().use { it.readAllBytes() }
            }.getOrElse { failure ->
                statusText = AssetManagerLang.CANNOT_READ.lang(asset.location, failure.message.orEmpty())
                return
            }
        }
        val typeId = modelTypeId ?: assetFileTypeId(scope, asset.location.path, bytes, forceText)
        if (typeId == null) {
            statusText = AssetManagerLang.NO_PREVIEW.lang(asset.location)
            return
        }
        val path = "resource://${scope.name.lowercase()}/${scope.directory}/${asset.location.namespace}/${asset.location.path}"
        when (val result = model.openVirtual(path, typeId, bytes)) {
            HollowIdeOpenResult.Directory -> Unit
            HollowIdeOpenResult.Unsupported -> statusText = AssetManagerLang.CANNOT_OPEN.lang(asset.location)
            is HollowIdeOpenResult.File -> openFileDockItem(result.file)
        }
    }

    private fun overrideAssetFile(scope: AssetResourceScope, asset: AssetFile) {
        val path = asset.projectPath(scope)
        if (asset.state == AssetResourceState.OVERRIDDEN) {
            openProjectAsset(path, asset)
            return
        }
        val manager = assetResourceManager(scope)
        if (manager == null) {
            statusText = AssetManagerLang.SERVER_UNAVAILABLE.lang
            return
        }
        val bytes = runCatching {
            manager.readAsset(scope, asset, original = asset.state == AssetResourceState.HIDDEN)
        }.getOrElse { failure ->
            statusText = AssetManagerLang.CANNOT_OVERRIDE.lang(asset.location, failure.message.orEmpty())
            return
        }
        if (model.replaceFile(path, bytes) != HollowIdeFileOperationResult.Success) {
            statusText = AssetManagerLang.CANNOT_OVERRIDE.lang(asset.location, path)
            return
        }
        openProjectAsset(path, asset)
    }

    private fun hideAssetFile(scope: AssetResourceScope, asset: AssetFile) {
        val result = model.replaceFile(asset.projectPath(scope), ByteArray(0))
        if (result != HollowIdeFileOperationResult.Success) {
            statusText = AssetManagerLang.CANNOT_HIDE.lang(asset.location, result.name)
        }
    }

    private fun restoreAssetFile(scope: AssetResourceScope, asset: AssetFile) {
        val result = model.delete(listOf(asset.projectPath(scope)))
        if (result != HollowIdeFileOperationResult.Success) {
            statusText = AssetManagerLang.CANNOT_RESTORE.lang(asset.location, result.name)
        }
    }

    private fun openProjectAsset(path: String, asset: AssetFile) {
        when (val result = model.openFile(path)) {
            HollowIdeOpenResult.Directory -> Unit
            HollowIdeOpenResult.Unsupported -> statusText = AssetManagerLang.CANNOT_OPEN.lang(asset.location)
            is HollowIdeOpenResult.File -> openFileDockItem(result.file)
        }
    }

    private fun handleDockShortcut(key: Int, modifiers: Int): Boolean {
        val command = modifiers and GLFW.GLFW_MOD_CONTROL != 0
        if (!command || key != GLFW.GLFW_KEY_W) return false
        return dock.closeFocused()
    }

    /** Ctrl+N opens the project-wide search overlay, wherever the focus is. */
    private fun handleSearchOverlayShortcut(key: Int, modifiers: Int): Boolean {
        if (modifiers and GLFW.GLFW_MOD_CONTROL == 0) return false
        if (modifiers and GLFW.GLFW_MOD_SHIFT != 0 || modifiers and GLFW.GLFW_MOD_ALT != 0) return false
        if (key != GLFW.GLFW_KEY_N) return false
        search.open(focusedEditorFile()?.let { editorStates[it.path]?.selectedText() })
        return true
    }

    /** Project filtering must work even when the tree itself does not own keyboard focus. */
    private fun handleProjectFilterShortcut(key: Int, modifiers: Int): Boolean {
        if (dock.focusedItemId != ProjectTreeId || key != GLFW.GLFW_KEY_F) return false
        if (modifiers and GLFW.GLFW_MOD_CONTROL == 0) return false
        if (modifiers and (GLFW.GLFW_MOD_SHIFT or GLFW.GLFW_MOD_ALT) != 0) return false
        projectFilter.open()
        return true
    }

    /** Moves the keyboard focus to [nodeId] once the pending frame has been built. */
    internal fun focusSurface(nodeId: String) {
        pipeline.await()
        surface.runtime.focus(nodeId)
    }

    /** Defers focus until the asynchronous composition that created [nodeId] has completed. */
    private fun requestSurfaceFocus(nodeId: String) {
        Minecraft.getInstance().execute { focusSurface(nodeId) }
    }

    /** Editor-wide shortcuts that work wherever the focus sits inside the IDE. */
    private fun handleEditorShortcut(key: Int, modifiers: Int): Boolean {
        val control = modifiers and GLFW.GLFW_MOD_CONTROL != 0
        val shift = modifiers and GLFW.GLFW_MOD_SHIFT != 0
        val alt = modifiers and GLFW.GLFW_MOD_ALT != 0
        if (key == GLFW.GLFW_KEY_F3 && !control && !alt) {
            return focusedEditorFile()?.let { navigateFind(it, if (shift) -1 else 1) } == true
        }
        if (!control) return false
        return when {
            alt && key == GLFW.GLFW_KEY_L -> focusedEditorFile()?.let(::formatFile) == true
            !alt && key == GLFW.GLFW_KEY_F -> openFind(replace = false)
            !alt && key == GLFW.GLFW_KEY_R -> openFind(replace = true)
            !alt && key == GLFW.GLFW_KEY_S -> focusedFile()?.let { file ->
                model.save(file.path).also { dock.updateItem(file.dockItem()) }
            } == true

            else -> false
        }
    }

    /**
     * Opens find (or find/replace) on the file being edited, seeded with its selection. Bound to the
     * editor rather than to a focused text field, so Ctrl+F never hijacks the project filter.
     */
    private fun openFind(replace: Boolean): Boolean {
        val file = focusedEditorFile() ?: return false
        val editor = editorStates[file.path]
        val state = findStates.getOrPut(file.path) { HollowIdeFindState(file.id) }
        state.open(replace, editor?.selectedText())
        val matches = state.matches(file.text)
        if (matches.isNotEmpty()) state.currentIndex = state.indexFrom(matches, editor?.caret ?: 0)
        return true
    }

    private fun closeFind(file: HollowIdeOpenFile) {
        findStates[file.path]?.close()
        findStates.remove(file.path)
        Minecraft.getInstance().execute {
            pipeline.await()
            surface.runtime.focus("editor-${file.id}")
        }
    }

    /** Moves to the next/previous match and selects it, which also scrolls the editor onto it. */
    private fun navigateFind(file: HollowIdeOpenFile, delta: Int): Boolean {
        val state = findStates[file.path]?.takeIf { it.visible } ?: return false
        val matches = state.matches(file.text)
        if (matches.isEmpty()) {
            statusText = if (state.query.isBlank()) "" else FindLang.NO_RESULTS_FOR.lang(state.query)
            return true
        }
        val size = matches.size
        state.currentIndex = ((state.currentIndex + delta) % size + size) % size
        val match = matches[state.currentIndex]
        editorState(file).setSelection(match.first, match.last + 1)
        statusText = FindLang.POSITION.lang(state.currentIndex + 1, size)
        return true
    }

    private fun replaceCurrentMatch(file: HollowIdeOpenFile) {
        val state = findStates[file.path]?.takeIf { it.visible } ?: return
        if (file.readOnly) {
            statusText = EditorLang.READ_ONLY.lang(file.title)
            return
        }
        val text = file.text
        val matches = state.matches(text)
        if (matches.isEmpty()) return
        val index = state.currentIndex.coerceIn(0, matches.lastIndex)
        val match = matches[index]
        val replacement = state.expandReplacement(text, match)
        val next = text.substring(0, match.first) + replacement + text.substring(match.last + 1)
        applyEditorText(file, next, match.first + replacement.length)
        state.currentIndex = index.coerceAtMost((state.matches(file.text).size - 1).coerceAtLeast(0))
    }

    private fun replaceAllMatches(file: HollowIdeOpenFile) {
        val state = findStates[file.path]?.takeIf { it.visible } ?: return
        if (file.readOnly) {
            statusText = EditorLang.READ_ONLY.lang(file.title)
            return
        }
        val text = file.text
        val matches = state.matches(text)
        if (matches.isEmpty()) return
        val next = buildString {
            var cursor = 0
            for (match in matches) {
                append(text, cursor, match.first)
                append(state.expandReplacement(text, match))
                cursor = match.last + 1
            }
            append(text, cursor, text.length)
        }
        applyEditorText(file, next, next.length.coerceAtMost(editorState(file).caret))
        state.currentIndex = 0
        statusText = FindLang.REPLACED.lang(matches.size)
    }

    /** Writes [next] into the editor as one undoable edit and keeps the model and tab in step. */
    private fun applyEditorText(file: HollowIdeOpenFile, next: String, caret: Int) {
        val editor = editorState(file)
        if (!editor.applyEdit(next, listOf(UiTextCaret(caret.coerceIn(0, next.length))))) return
        model.updateText(file.path, editor.text)
        dock.updateItem(file.dockItem())
    }

    private fun openSearchResult(result: HollowIdeSearchResult) {
        search.close()
        openDefinition(DefinitionLocation(result.path, result.offset))
    }

    /**
     * Reformats a file off the render thread and puts the result back as one undoable edit, with
     * the caret kept on the line it was on.
     */
    private fun formatFile(file: HollowIdeOpenFile): Boolean {
        if (file.readOnly || file.textOrNull == null) return false
        val session = editorSession(file.path)
        if (!session.canFormat) {
            statusText = "No formatter for ${file.title}"
            return false
        }
        val editor = editorState(file)
        val text = editor.text
        fileContextMenu = null
        session.format(text) { formatted ->
            when {
                formatted == null -> statusText = "${file.title} is already formatted"
                editor.text != text -> statusText = "${file.title} changed while formatting"
                else -> {
                    val caret = mapCaretThroughFormat(text, formatted, editor.caret)
                    editor.applyEdit(formatted, listOf(UiTextCaret(caret)))
                    model.updateText(file.path, formatted)
                    dock.updateItem(file.dockItem())
                    statusText = "Reformatted ${file.title}"
                }
            }
        }
        return true
    }

    private fun editorTarget(): DockTarget {
        model.files.values.firstOrNull { dock.contains(it.id) }
            ?.let { dock.stackIdOf(it.id) }
            ?.let { return DockTarget(it) }
        dock.stackIdOf(ProjectTreeId)?.let { return DockTarget(it, DockPlacement.RIGHT) }
        return DockTarget.Root
    }

    private fun focusedFile(): HollowIdeOpenFile? {
        val focused = dock.focusedItemId ?: return null
        return model.files.values.firstOrNull { it.id == focused }
    }

    private fun goToDefinition(): Boolean {
        val file = focusedEditorFile() ?: return false
        val editorKey = "editor-${file.id}"
        val editor = editorStates[file.path] ?: return false
        if (surface.runtime.focusedKey != editorKey) surface.runtime.focus(editorKey)
        val session = editorSession(file.path)
        statusText = ""
        session.resolveDefinition(file.text, editor.caret) { definition ->
            if (definition == null) {
                statusText = "Definition not found"
                return@resolveDefinition
            }
            openDefinition(definition)
        }
        return true
    }

    private fun focusedEditorFile(): HollowIdeOpenFile? {
        surface.runtime.focusedKey?.removePrefix("editor-")?.takeIf { it != surface.runtime.focusedKey }
            ?.let { editorFileId ->
                model.files.values.firstOrNull { it.id == editorFileId && it.textOrNull != null }?.let { return it }
            }
        return focusedFile()?.takeIf { it.textOrNull != null }
    }

    /** Handles a click on a clickable inlay hint, such as the "open" button on a location. */
    private fun runInlayAction(file: HollowIdeOpenFile, action: UiInlayAction) {
        when (val decoded = InlayAction.decode(action.id)) {
            is InlayAction.OpenResource -> {
                val definition = ResourceLocationTargets.definition(decoded.location)
                if (definition == null) {
                    statusText = "Cannot find '${decoded.location}'"
                } else {
                    openDefinition(definition)
                }
            }

            is InlayAction.PickColor -> openColorPicker(file, action.range?.let {
                decoded.copy(start = it.first, end = it.last + 1)
            } ?: decoded)

            null -> statusText = "Unsupported inlay action"
        }
    }

    private fun openColorPicker(file: HollowIdeOpenFile, action: InlayAction.PickColor) {
        if (file.readOnly) {
            statusText = EditorLang.READ_ONLY.lang(file.title)
            return
        }
        val text = file.text
        if (action.start < 0 || action.end !in action.start..text.length ||
            text.substring(action.start, action.end) != action.literal
        ) {
            statusText = EditorLang.COLOR_MOVED.lang
            return
        }
        val color = runCatching { parseColor(action.literal) }.getOrNull() ?: return
        colorPicker = EditorColorPicker(
            path = file.path,
            text = text,
            start = action.start,
            end = action.end,
            color = color,
            x = surface.runtime.mouseX,
            y = surface.runtime.mouseY,
        )
    }

    @Composable
    private fun EditorColorPickerPopup() {
        val picker = colorPicker ?: return
        Popup(
            anchorBounds = UiRect(picker.x, picker.y, 0f, 0f),
            alignment = UiPopupAlignment.Cursor,
            id = "ide-color-picker",
            tags = listOf("dropdown-popup", "ide-color-picker"),
            onDismiss = { colorPicker = null },
        ) {
            ColorPicker(
                value = picker.color,
                onValueChange = { next -> applyPickedColor(next) },
            )
        }
    }

    private fun applyPickedColor(color: UiColor) {
        val picker = colorPicker ?: return
        val file = model.files[picker.path] ?: return
        val text = file.text
        if (text != picker.text) {
            colorPicker = null
            statusText = EditorLang.COLOR_MOVED.lang
            return
        }
        val literal = hssColorLiteralText(color.toArgb())
        val next = text.substring(0, picker.start) + literal + text.substring(picker.end)
        applyEditorText(file, next, picker.start + literal.length)
        colorPicker = picker.copy(text = next, end = picker.start + literal.length, color = color)
    }

    private fun openDefinition(definition: DefinitionLocation) {
        val file = if (definition.text != null || definition.readOnly) {
            model.openReadOnly(definition.path, definition.text.orEmpty())
        } else {
            when (val result = model.openFile(definition.path)) {
                HollowIdeOpenResult.Unsupported -> {
                    statusText = "Unsupported definition target: ${definition.path}"
                    return
                }

                is HollowIdeOpenResult.File -> result.file
                HollowIdeOpenResult.Directory -> return
            }
        }
        openFileDockItem(file)
        focusEditorAt(file, definition.offset)
    }

    private fun focusEditorAt(file: HollowIdeOpenFile, offset: Int) {
        Minecraft.getInstance().execute {
            pipeline.await()
            val editorKey = "editor-${file.id}"
            if (file.type.id == BuiltinTextFileTypeId) {
                val editor = editorState(file)
                editor.moveCaret(offset)
            }
            surface.runtime.focus(editorKey)
        }
    }

    private fun renderOverlay(target: UiRenderTarget) {
        val window = Minecraft.getInstance().window
        val frameWidth = HollowIdeScale.scaledWidth()
        val frameHeight = HollowIdeScale.scaledHeight()
        val frame = (if (PIPELINE_FRAMES) pipeline.take(frameWidth, frameHeight) else null)
            ?: surface.frame(frameWidth, frameHeight, lastMouseX, lastMouseY, System.nanoTime())
        renderer.render(frame, target)
        // Only ask for the cursor while the pointer is actually over the IDE; anywhere else the
        // world and its gizmo are free to have it.
        val overIde = surface.runtime.lastFrame?.hitsVisible(lastMouseX, lastMouseY) == true
        UiCursorManager.claim(
            window = window.window,
            owner = this,
            shape = surface.runtime.cursor.takeIf { overIde },
        )
        if (PIPELINE_FRAMES) {
            val mouseX = lastMouseX
            val mouseY = lastMouseY
            pipeline.schedule(frameWidth, frameHeight) {
                surface.frame(frameWidth, frameHeight, mouseX, mouseY, System.nanoTime())
            }
        }
    }

    private fun currentBlitTarget(): UiRenderTarget {
        val viewport = IntArray(4)
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)
        val logicalWidth = HollowIdeScale.scaledWidth()
        val logicalHeight = HollowIdeScale.scaledHeight()
        return UiRenderTarget(
            framebufferId = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
            x = viewport[0],
            y = viewport[1],
            width = viewport[2],
            height = viewport[3],
            logicalWidth = logicalWidth,
            logicalHeight = logicalHeight,
            scale = viewport[2] / logicalWidth,
        )
    }

    private fun isAvailable(): Boolean {
        return when (HollowEngineConfig.editMode) {
            EditMode.DISABLED -> false
            EditMode.CHAT_ONLY -> Minecraft.getInstance().screen is ChatScreen
            else -> true
        }
    }

}

internal object EditorLang {
    private const val ROOT = "hollowengine.gui.ide.editor."

    const val COLOR_MOVED = ROOT + "color_moved"
    const val READ_ONLY = ROOT + "read_only"
}

private data class EditorColorPicker(
    val path: String,
    val text: String,
    val start: Int,
    val end: Int,
    val color: UiColor,
    val x: Float,
    val y: Float,
)

private const val DefaultDiagnosticsPanelHeight = 160f
private const val MinDiagnosticsPanelHeight = 90f
private const val MaxDiagnosticsPanelHeight = 360f
