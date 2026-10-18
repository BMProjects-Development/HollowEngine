package ru.hollowhorizon.hollowengine.client.ui.style

// Value parsers for the motion properties: transitions, animations and the keyframe
// property names an animation touches.

internal fun parseAnimations(value: String): List<UiAnimation> {
    if (value.equals("none", ignoreCase = true)) return emptyList()
    return splitTopLevel(value, ',').map(::parseAnimation)
}

/**
 * One `animations` entry. Tokens are order-free (as in CSS), so each one is classified by
 * its own shape; [animationSlotAt] mirrors this classification for the IDE.
 */
private fun parseAnimation(value: String): UiAnimation {
    var name: String? = null
    var duration: Long? = null
    var delay = 0L
    var easing: TransitionEasing = TransitionEasing.LINEAR
    var iterationCount = 1f
    var direction = UiAnimationDirection.NORMAL
    var fillMode = UiAnimationFillMode.NONE
    var playState = UiAnimationPlayState.RUNNING
    for (part in splitTopLevelWhitespace(value)) {
        val cleaned = part.trim()
        when {
            cleaned.isDurationToken() && duration == null -> duration = parseDuration(cleaned)
            cleaned.isDurationToken() -> delay = parseDuration(cleaned)
            cleaned.isEasingToken() -> easing = parseEasing(cleaned)
            cleaned.isIterationCountToken() -> iterationCount = parseIterationCount(cleaned)
            cleaned.isEnumToken<UiAnimationDirection>() -> direction = parseAnimationDirection(cleaned)
            cleaned.isEnumToken<UiAnimationFillMode>() -> fillMode = parseAnimationFillMode(cleaned)
            cleaned.isEnumToken<UiAnimationPlayState>() -> playState = parseAnimationPlayState(cleaned)
            else -> name = unquote(cleaned)
        }
    }
    return UiAnimation(
        name = name ?: "",
        durationMillis = duration ?: 0L,
        easing = easing,
        delayMillis = delay,
        iterationCount = iterationCount,
        direction = direction,
        fillMode = fillMode,
        playState = playState,
    )
}

/**
 * Slot an `animations` token binds to, classified exactly like [parseAnimation] does, so
 * the editor labels `fade 200ms ease-out 100ms infinite` the way the engine reads it.
 */
internal fun animationSlotAt(tokens: List<String>, index: Int): HssSlot? {
    val token = tokens.getOrNull(index)?.trim() ?: return null
    val firstDuration = tokens.indexOfFirst { it.trim().isDurationToken() }
    return when {
        token.isDurationToken() && index == firstDuration -> AnimationDurationSlot
        token.isDurationToken() -> AnimationDelaySlot
        token.isEasingToken() -> AnimationEasingSlot
        token.isIterationCountToken() -> AnimationIterationSlot
        token.isEnumToken<UiAnimationDirection>() -> AnimationDirectionSlot
        token.isEnumToken<UiAnimationFillMode>() -> AnimationFillModeSlot
        token.isEnumToken<UiAnimationPlayState>() -> AnimationPlayStateSlot
        else -> AnimationNameSlot
    }
}

internal val AnimationNameSlot = slot("name", HssValueKind.KEYFRAMES)
internal val AnimationDurationSlot = slot("duration", HssValueKind.DURATION)
internal val AnimationEasingSlot = slot("easing", HssValueKind.EASING, optional = true)
internal val AnimationDelaySlot = slot("delay", HssValueKind.DURATION, optional = true)
internal val AnimationIterationSlot =
    HssSlot("iterations", HssValueKind.NUMBER, optional = true, keywords = listOf("infinite"))
internal val AnimationDirectionSlot =
    HssSlot("direction", HssValueKind.KEYWORD, optional = true, keywords = enumKeywords<UiAnimationDirection>())
internal val AnimationFillModeSlot =
    HssSlot("fill-mode", HssValueKind.KEYWORD, optional = true, keywords = enumKeywords<UiAnimationFillMode>())
internal val AnimationPlayStateSlot =
    HssSlot("play-state", HssValueKind.KEYWORD, optional = true, keywords = enumKeywords<UiAnimationPlayState>())

/** Every slot an `animations` entry may carry, in the order CSS writes them. */
internal val AnimationSlots = listOf(
    AnimationNameSlot,
    AnimationDurationSlot,
    AnimationEasingSlot,
    AnimationDelaySlot,
    AnimationIterationSlot,
    AnimationDirectionSlot,
    AnimationFillModeSlot,
    AnimationPlayStateSlot,
)

internal fun <T> List<UiAnimation>?.patchAnimationValues(
    values: List<T>,
    patch: (UiAnimation, T) -> UiAnimation,
): List<UiAnimation> {
    if (values.isEmpty()) return orEmpty()
    val base = this?.takeIf { it.isNotEmpty() } ?: List(values.size) { UiAnimation("") }
    return base.mapIndexed { index, animation -> patch(animation, values[index % values.size]) }
}

internal fun parseTransitions(value: String): List<UiTransition> {
    if (value.equals("none", ignoreCase = true)) return emptyList()
    return splitTopLevel(value, ',').map { entry ->
        val parts = splitTopLevelWhitespace(entry)
        UiTransition(
            property = parts[0],
            durationMillis = parseDuration(parts.getOrElse(1) { "0ms" }),
            easing = parseEasing(parts.getOrElse(2) { "linear" }),
        )
    }
}

internal fun parseDuration(value: String): Long {
    val cleaned = value.trim()
    if (cleaned.endsWith("ms")) return cleaned.dropLast(2).toLong()
    if (cleaned.endsWith("s")) return (cleaned.dropLast(1).toFloat() * 1000f).toLong()
    return cleaned.toLong()
}

internal fun parseIterationCount(value: String): Float {
    return if (value.equals("infinite", ignoreCase = true)) {
        Float.POSITIVE_INFINITY
    } else {
        value.toFloat().coerceAtLeast(0f)
    }
}

internal fun parseAnimationDirection(value: String): UiAnimationDirection = parseEnum(value, "animation direction")

internal fun parseAnimationFillMode(value: String): UiAnimationFillMode = parseEnum(value, "animation fill mode")

internal fun parseAnimationPlayState(value: String): UiAnimationPlayState = parseEnum(value, "animation play state")

internal fun parseEasing(value: String): TransitionEasing {
    val cleaned = value.trim().lowercase()
    return when {
        cleaned == "linear" -> TransitionEasing.LINEAR
        cleaned == "ease" -> TransitionEasing.EASE_IN_OUT
        cleaned == "ease-in" -> TransitionEasing.EASE_IN
        cleaned == "ease-out" -> TransitionEasing.EASE_OUT
        cleaned == "ease-in-out" -> TransitionEasing.EASE_IN_OUT
        cleaned == "step-start" -> TransitionEasing.Steps(1, TransitionEasing.StepPosition.START)
        cleaned == "step-end" -> TransitionEasing.Steps(1, TransitionEasing.StepPosition.END)
        cleaned.startsWith("steps(") -> parseSteps(cleaned)
        cleaned.startsWith("step(") -> parseSteps(cleaned.replaceFirst("step(", "steps("))
        cleaned.startsWith("cubic-bezier(") -> parseCubicBezier(cleaned)
        else -> TransitionEasing.LINEAR
    }
}

private fun parseSteps(value: String): TransitionEasing.Steps {
    val args = functionArgs(value, "steps")
    val count = args.firstOrNull()?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val position = when (args.getOrNull(1)?.trim()?.lowercase()) {
        "start", "jump-start" -> TransitionEasing.StepPosition.START
        else -> TransitionEasing.StepPosition.END
    }
    return TransitionEasing.Steps(count, position)
}

private fun parseCubicBezier(value: String): TransitionEasing.CubicBezier {
    val args = functionArgs(value, "cubic-bezier").map { it.trim().toFloat() }
    require(args.size == 4) { "cubic-bezier requires four numbers" }
    return TransitionEasing.CubicBezier(
        x1 = args[0].coerceIn(0f, 1f),
        y1 = args[1],
        x2 = args[2].coerceIn(0f, 1f),
        y2 = args[3],
    )
}

internal val UiNamedEasings = listOf("linear", "ease", "ease-in", "ease-out", "ease-in-out", "step-start", "step-end")

internal fun String.isDurationToken(): Boolean {
    val cleaned = trim().lowercase()
    return cleaned.removeSuffix("ms").toFloatOrNull() != null ||
            cleaned.removeSuffix("s").takeIf { cleaned.endsWith("s") }?.toFloatOrNull() != null
}

internal fun String.isEasingToken(): Boolean {
    val cleaned = trim().lowercase()
    return cleaned in UiNamedEasings ||
            cleaned.startsWith("steps(") ||
            cleaned.startsWith("step(") ||
            cleaned.startsWith("cubic-bezier(")
}

private fun String.isIterationCountToken(): Boolean =
    equals("infinite", ignoreCase = true) || trim().toFloatOrNull() != null

private inline fun <reified T : Enum<T>> String.isEnumToken(): Boolean =
    enumValues<T>().any { it.name.equals(trim().replace('-', '_'), ignoreCase = true) }

/**
 * Animated properties a keyframe declaration touches, in the dotted form the animation
 * engine tracks. Composite properties expand to the parts they actually write.
 */
internal fun keyframeProperties(property: String, value: String): Set<String> {
    return when (val canonical = HssSchema.find(property)?.name ?: property.lowercase()) {
        "translate" -> TransformTranslateProperties
        "rotate" -> TransformRotateProperties
        "scale" -> TransformScaleProperties
        "pivot" -> setOf("transform.pivot")
        "perspective" -> setOf("transform.perspective")
        "transform" -> transformKeyframeProperties(value)
        in TransformAxisProperties -> setOf(TransformAxisProperties.getValue(canonical))
        else -> setOf(canonical)
    }
}

private fun transformKeyframeProperties(value: String): Set<String> {
    if (value.equals("none", ignoreCase = true)) return TransformProperties
    return parseValueFunctions(value).flatMap { (name, _) ->
        when (name) {
            "translate" -> TransformTranslateProperties
            "translatex" -> setOf("transform.translate.x")
            "translatey" -> setOf("transform.translate.y")
            "translatez" -> setOf("transform.translate.z")
            "rotate" -> setOf("transform.rotate.z")
            "rotatex" -> setOf("transform.rotate.x")
            "rotatey" -> setOf("transform.rotate.y")
            "rotatez" -> setOf("transform.rotate.z")
            "scale" -> TransformScaleProperties
            "scalex" -> setOf("transform.scale.x")
            "scaley" -> setOf("transform.scale.y")
            "scalez" -> setOf("transform.scale.z")
            "perspective" -> setOf("transform.perspective")
            else -> emptySet()
        }
    }.toSet()
}

/** The single animated property each per-axis long-hand writes. */
private val TransformAxisProperties = buildMap {
    for (axis in listOf("x", "y", "z")) {
        put("translate-$axis", "transform.translate.$axis")
        put("rotate-$axis", "transform.rotate.$axis")
        put("scale-$axis", "transform.scale.$axis")
    }
}

private val TransformTranslateProperties = setOf(
    "transform.translate.x",
    "transform.translate.y",
    "transform.translate.z",
)

private val TransformRotateProperties = setOf(
    "transform.rotate.x",
    "transform.rotate.y",
    "transform.rotate.z",
)

private val TransformScaleProperties = setOf(
    "transform.scale.x",
    "transform.scale.y",
    "transform.scale.z",
)

private val TransformProperties = TransformTranslateProperties +
        TransformRotateProperties +
        TransformScaleProperties +
        setOf("transform.pivot", "transform.perspective")
