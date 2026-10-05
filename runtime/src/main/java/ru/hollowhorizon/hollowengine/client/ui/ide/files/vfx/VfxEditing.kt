package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import androidx.compose.runtime.mutableStateMapOf
import ru.hollowhorizon.hollowengine.common.vfx.VfxEffect

/**
 * An effect being edited, wherever it lives: a `.vfx` file, or the copy of it one entity plays. The inspector
 * and the node tree edit both the same way.
 */
interface VfxEditing {
    val effect: VfxEffect

    /**
     * Replaces the effect with what [change] makes of it; edits sharing [mergeKey] in quick succession undo
     * together, and one made without [history] is not undone at all.
     */
    fun edit(mergeKey: String? = null, history: Boolean = true, change: (VfxEffect) -> VfxEffect)

    /** Starts a gesture, such as dragging a handle, that should go back in one step. */
    fun beginGesture()

    fun endGesture()

    fun undo(): Boolean

    fun redo(): Boolean
}

/** What the inspector of an effect keeps from one node to the next: which sections are open, and the material it previews. */
internal open class VfxInspectorState {
    private val sections = mutableStateMapOf<String, Boolean>()

    /** The material of the selected surface on its own, in the material section of the inspector. */
    val materialPreview = materialPreviewState()

    fun isSectionOpen(key: String): Boolean = sections[key] ?: false

    fun toggleSection(key: String) {
        sections[key] = !isSectionOpen(key)
    }
}

/** Which node of an effect is selected, wherever its tree is shown. */
internal interface VfxNodeSelection {
    val selected: String?

    fun select(id: String?)

    /** Opens [parent] in the tree, so a node just put under it shows; null is the effect itself. */
    fun reveal(parent: String?)
}
