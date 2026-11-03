@file:OptIn(ExperimentalSerializationApi::class)

package ru.hollowhorizon.hollowengine.common.attachments.editor

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * What the component editor knows about a component beyond its serial form.
 */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
annotation class EditorName(val name: String)

/** The line under a field, or under a component's header. */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
annotation class EditorDescription(val description: String)

/** A `namespace:textures/...` icon shown beside the component, field or story action. */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
annotation class EditorIcon(val icon: String)

/**
 * Groups a component under a heading in the "add component" list, or a story action in the "add command" one.
 *
 * @param color the `#RRGGBB` the things of the category are told apart by; left empty, the category is given one.
 */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class EditorCategory(val category: String, val color: String = "")

/** Kept in the data, never shown. For fields gameplay owns and a human should not type into, or actions nothing offers. */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
annotation class EditorHidden

/**
 * A field a timeline does not offer to animate, though it holds a number or a flag: one that is read once, as
 * whether an effect starts by itself, or that changes what is built rather than how it moves.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class EditorNotAnimated

/**
 * Bounds a numeric field, and turns it into a slider when [slider] is set. An infinite bound is no bound.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class EditorRange(
    val min: Double = Double.NEGATIVE_INFINITY,
    val max: Double = Double.POSITIVE_INFINITY,
    val slider: Boolean = false,
)

/** JSON value used when enabling a nullable field; leaves its runtime default unchanged. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class EditorDefault(val json: String)

@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class EditorBone

/**
 * Edits the field with the editor registered under [id] instead of the one its type would get; on a story action,
 * draws its rows with the widget registered under [id].
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER)
annotation class EditorWidget(val id: String)

/** The field editors the engine registers, by the id [EditorWidget] names them with. */
object EditorWidgets {
    /** A list of names of the colliders on the same bone as the attachment, ticked off among those there are. */
    const val COLLIDERS = "hollowengine:colliders"

    /** The name of one IK target of the rig, picked among those there are. */
    const val RIG_TARGETS = "hollowengine:rig_targets"

    /** An expression of the animator's language, with its completion and highlighting. */
    const val ANIMATION_EXPRESSION = "hollowengine:animation_expression"

    /** A sound event or a sound file, offered among those there are, with a button to listen to it. */
    const val SOUND_SOURCE = "hollowengine:sound_source"
}

/** A string field that deserves more than one line. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class EditorMultiline

/**
 * A string field, or parameter of a story action, holding a path to an asset, so the editor can offer what exists instead
 * of asking the user to remember it. [extensions] is matched against the end of the path (`gltf`, `node.kts`, `png`).
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
annotation class EditorAsset(vararg val extensions: String)

/**
 * A parameter of a story action naming a clip of the model of the entity the action is called on, so the editor offers
 * the clips of that model and warns about one it lacks, when it can tell which model that is.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class EditorAnimation

/** A parameter of a story action naming the model of the entity the action gives back. */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class EditorModel

/** A parameter of a story action naming, by the author's id, the NPC the action gives back. */
@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class EditorNpcId
