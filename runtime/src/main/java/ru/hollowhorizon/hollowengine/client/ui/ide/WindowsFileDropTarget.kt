package ru.hollowhorizon.hollowengine.client.ui.ide

import com.sun.jna.*
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import ru.hollowhorizon.hollowengine.HollowEngine
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Registered on the GLFW window thread; OLE drives hover feedback before the mouse is released. */
internal class WindowsFileDropTarget(
    private val window: HWND,
    private val onMove: (List<File>, Int, Int) -> Boolean,
    private val onLeave: () -> Unit,
    private val onDrop: (List<File>, Int, Int) -> Boolean,
) : AutoCloseable {
    private var files = emptyList<File>()
    val active: Boolean get() = files.isNotEmpty()
    private var closed = false
    private val callbacks = WindowsDropTargetCallbacks(
        enter = { data, point, effect ->
            leave()
            files = readWindowsDraggedFiles(data)
            if (active) User32.INSTANCE.SetForegroundWindow(window)
            move(point, effect)
        },
        over = ::move,
        leave = ::leave,
        onFailure = ::leave,
        drop = { data, point, effect ->
            try {
                // Read again at Drop: sources may publish delayed file data on release.
                files = readWindowsDraggedFiles(data)
                val local = clientPoint(point)
                effect.value = if (effect.value and WindowsDropEffectCopy != 0 && active &&
                    onMove(files, local.x, local.y) && onDrop(files, local.x, local.y)) WindowsDropEffectCopy else 0
            } finally {
                leave()
            }
        },
    )

    init {
        val initialized = Ole32.INSTANCE.OleInitialize(Pointer.NULL).toInt()
        if (initialized < 0) {
            callbacks.close()
            error("OleInitialize failed: ${initialized.toUInt().toString(16)}")
        }
        val registered = WindowsApis.ole32.RegisterDragDrop(window, callbacks.pointer)
        if (registered < 0) {
            callbacks.close()
            Ole32.INSTANCE.OleUninitialize()
            error("RegisterDragDrop failed: ${registered.toUInt().toString(16)}")
        }
    }

    private fun clientPoint(point: WindowsDropPoint): POINT = POINT(point.x, point.y).also {
        check(WindowsApis.user32.ScreenToClient(window, it)) { "Could not locate the file drop in the window" }
    }

    private fun move(point: WindowsDropPoint, effect: IntByReference) {
        val local = clientPoint(point)
        effect.value = if (active && effect.value and WindowsDropEffectCopy != 0 &&
            onMove(files, local.x, local.y)) WindowsDropEffectCopy else 0
    }

    private fun leave() {
        files = emptyList()
        onLeave()
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            WindowsApis.ole32.RevokeDragDrop(window)
            leave()
        } finally {
            callbacks.close()
            Ole32.INSTANCE.OleUninitialize()
        }
    }
}

internal fun readWindowsDraggedFiles(pointer: Pointer): List<File> {
    val medium = WindowsClipboardDataObject(pointer).requestData(WindowsFileDropFormat, -1, StorageGlobal)
        ?: return emptyList()
    return try {
        if (medium.tymed != StorageGlobal) emptyList()
        else medium.data?.let(WindowsDropFileClipboard::readFiles).orEmpty()
    } finally {
        releaseWindowsStorageMedium(medium)
    }
}

/** POINTL is passed by value in IDropTarget, including on 64-bit Windows. */
@Structure.FieldOrder("x", "y")
internal class WindowsDropPoint : Structure(), Structure.ByValue {
    @JvmField var x: Int = 0
    @JvmField var y: Int = 0
}

/** Owns the COM vtable and keeps its JNA callbacks alive until the last native Release. */
internal class WindowsDropTargetCallbacks(
    enter: (Pointer, WindowsDropPoint, IntByReference) -> Unit,
    over: (WindowsDropPoint, IntByReference) -> Unit,
    leave: () -> Unit,
    drop: (Pointer, WindowsDropPoint, IntByReference) -> Unit,
    private val onFailure: () -> Unit = {},
) : AutoCloseable {
    private val references = AtomicInteger(1)
    private val table = Memory(7L * Native.POINTER_SIZE)
    val pointer = Memory(Native.POINTER_SIZE.toLong())
    private val callbacks: List<Callback> = listOf(
        QueryInterface { _, iid, result ->
            val id = GUID(iid).toGuidString()
            if (id.equals(UnknownId, true) || id.equals(DropTargetId, true)) {
                references.incrementAndGet()
                result.value = pointer
                0
            } else {
                result.value = null
                NoInterface
            }
        },
        ReferenceCount { references.incrementAndGet() },
        ReferenceCount { release() },
        DragData { _, data, _, point, effect -> guard(effect) { enter(data, point, effect) } },
        DragOver { _, _, point, effect -> guard(effect) { over(point, effect) } },
        DragLeave { guard(null, leave) },
        DragData { _, data, _, point, effect -> guard(effect) { drop(data, point, effect) } },
    )

    init {
        callbacks.forEachIndexed { index, callback ->
            table.setPointer(index.toLong() * Native.POINTER_SIZE, CallbackReference.getFunctionPointer(callback))
        }
        pointer.setPointer(0, table)
        liveTargets += this
    }

    private fun guard(effect: IntByReference?, action: () -> Unit): Int = try {
        action()
        0
    } catch (error: Throwable) {
        effect?.value = 0
        runCatching(onFailure)
        HollowEngine.LOGGER.warn("Could not handle native file drop", error)
        Failure
    }

    private fun release(): Int {
        val remaining = references.decrementAndGet()
        if (remaining == 0) {
            liveTargets -= this
            pointer.close()
            table.close()
        }
        return remaining
    }

    override fun close() { release() }

    companion object {
        private const val UnknownId = "{00000000-0000-0000-C000-000000000046}"
        private const val DropTargetId = "{00000122-0000-0000-C000-000000000046}"
        private const val NoInterface = -2147467262
        private const val Failure = -2147467259
        private val liveTargets = Collections.newSetFromMap(ConcurrentHashMap<WindowsDropTargetCallbacks, Boolean>())
    }
}

internal fun interface QueryInterface : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, iid: Pointer, result: PointerByReference): Int
}
internal fun interface ReferenceCount : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer): Int
}
internal fun interface DragData : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, data: Pointer, keys: Int, point: WindowsDropPoint, effect: IntByReference): Int
}
internal fun interface DragOver : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer, keys: Int, point: WindowsDropPoint, effect: IntByReference): Int
}
internal fun interface DragLeave : StdCallLibrary.StdCallCallback {
    fun invoke(self: Pointer): Int
}
