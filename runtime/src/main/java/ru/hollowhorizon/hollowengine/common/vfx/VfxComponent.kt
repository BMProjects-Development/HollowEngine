package ru.hollowhorizon.hollowengine.common.vfx

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.api.Registerable
import ru.hollowhorizon.hollowengine.api.Syncable
import ru.hollowhorizon.hollowengine.common.attachments.editor.*
import ru.hollowhorizon.hollowengine.common.models.AnimationExpression
import ru.hollowhorizon.hollowengine.common.scene.SceneObject
import ru.hollowhorizon.hollowengine.common.scripting.annotations.Attach

/**
 * An effect attached to bone, authored in the rig editor.
 */
@Serializable
@Registerable
@Syncable
@SerialName("hollowengine:rig/vfx")
@EditorName("hollowengine.gui.rig_editor.kind_vfx")
@EditorIcon("hollowengine:textures/gui/icons/files/effect.svg")
@Attach(SceneObject::class)
data class VfxComponent(
    @EditorHidden
    val id: String = "vfx",
    @EditorName("hollowengine.gui.vfx.effect")
    @EditorAsset(".vfx")
    val effect: String = "",
    /** Whether it starts as soon as the model is drawn; otherwise a script starts it. */
    @EditorName("hollowengine.gui.vfx.auto_play")
    @EditorNotAnimated
    val autoPlay: Boolean = true,
    /** A blank condition always emits. Conditions are evaluated against the host when it exists. */
    @EditorName("hollowengine.gui.vfx.active_while")
    @EditorDescription("hollowengine.gui.vfx.active_while.hint")
    @EditorWidget(EditorWidgets.ANIMATION_EXPRESSION)
    @EditorNotAnimated
    val activeWhile: AnimationExpression = AnimationExpression(""),
    @EditorHidden
    val ownEffect: VfxEffect? = null,
    @EditorHidden
    @EditorNotAnimated
    val emitting: Boolean = true,
)
