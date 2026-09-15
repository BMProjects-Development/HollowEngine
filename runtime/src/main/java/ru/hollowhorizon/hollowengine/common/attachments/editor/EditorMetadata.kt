@file:OptIn(ExperimentalSerializationApi::class)

package ru.hollowhorizon.hollowengine.common.attachments.editor

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * What the component editor knows about a component beyond its serial form.
 */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class EditorName(val name: String)

/** The line under a field, or under a component's header. */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class EditorDescription(val description: String)

/** A `namespace:textures/...` icon shown beside the component or field. */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class EditorIcon(val icon: String)

// TODO:
/** Groups a component under a heading in the "add component" list. */
@SerialInfo
@Target(AnnotationTarget.CLASS)
annotation class EditorCategory(val category: String)

/** Kept in the data, never shown. For fields gameplay owns and a human should not type into. */
@SerialInfo
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class EditorHidden

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

@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class EditorBone

/** A string field that deserves more than one line. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class EditorMultiline

/**
 * A string field holding a path to an asset, so the editor can offer what exists instead of asking the
 * user to remember it. [extensions] is matched against the end of the path (`gltf`, `node.kts`, `png`).
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class EditorAsset(vararg val extensions: String)
