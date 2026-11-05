package ru.hollowhorizon.hollowengine.addons.mcp.client

import com.mojang.blaze3d.platform.NativeImage
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.addons.mcp.McpTool
import ru.hollowhorizon.hollowengine.addons.mcp.ParameterType
import ru.hollowhorizon.hollowengine.addons.mcp.ToolArguments
import ru.hollowhorizon.hollowengine.addons.mcp.ToolInputException
import ru.hollowhorizon.hollowengine.addons.mcp.ToolParameter
import ru.hollowhorizon.hollowengine.addons.mcp.contentResult
import ru.hollowhorizon.hollowengine.addons.mcp.errorResult
import ru.hollowhorizon.hollowengine.client.camera.CameraPose
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import java.util.Base64
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.sqrt

internal fun screenshotTool() = McpTool(
    name = "screenshot",
    description = """
        Takes a picture of the game. By default it is what the player sees, HUD and open screens
        included; world_only leaves out the GUI and the hand. Given a camera position (x, y, z) it
        shows the world from there instead, facing yaw/pitch or towards look_at_x/y/z: the player sees
        a short wait screen meanwhile, and only what is loaded around the player can appear. Angles
        are the F3 ones: yaw 0 faces south (+Z), 90 west, 180 north, -90 east; pitch -90 is up, 90 down.
        A still frame cannot show whether an animation plays; check clip names with `he model entity`
        instead. Pictures cost a lot of context, so take them only for what needs one.
    """.trimIndent(),
    parameters = listOf(
        ToolParameter("world_only", ParameterType.BOOLEAN, "Leave out the HUD, any open screen and the hand"),
        ToolParameter("x", ParameterType.NUMBER, "Camera position X"),
        ToolParameter("y", ParameterType.NUMBER, "Camera position Y"),
        ToolParameter("z", ParameterType.NUMBER, "Camera position Z"),
        ToolParameter("yaw", ParameterType.NUMBER, "Camera yaw in degrees"),
        ToolParameter("pitch", ParameterType.NUMBER, "Camera pitch in degrees"),
        ToolParameter("look_at_x", ParameterType.NUMBER, "Point to face, instead of yaw and pitch"),
        ToolParameter("look_at_y", ParameterType.NUMBER, "Point to face, instead of yaw and pitch"),
        ToolParameter("look_at_z", ParameterType.NUMBER, "Point to face, instead of yaw and pitch"),
        ToolParameter("fov", ParameterType.NUMBER, "Vertical field of view in degrees; the player's by default"),
        ToolParameter("max_width", ParameterType.INTEGER, "Widest picture to return, $DEFAULT_WIDTH by default"),
    ),
    readOnly = true,
) { arguments ->
    val pose = cameraPose(arguments)
    val maxWidth = arguments.int("max_width", DEFAULT_WIDTH).coerceIn(MIN_WIDTH, MAX_WIDTH)
    val image = FrameCapture.capture(arguments.boolean("world_only", false), pose)
        ?: return@McpTool errorResult("No frame was drawn within 10 seconds; the game window may be minimized")
    val encoded = image.use { encode(it, maxWidth) }
    val caption = buildString {
        append("${encoded.width}x${encoded.height}")
        pose?.let { append(", camera at %.1f %.1f %.1f, yaw %.1f, pitch %.1f".format(Locale.ROOT, it.position.x, it.position.y, it.position.z, it.yaw, it.pitch)) }
    }
    contentResult(listOf(ImageContent(Base64.getEncoder().encodeToString(encoded.png), "image/png"), TextContent(caption)))
}

private fun cameraPose(arguments: ToolArguments): CameraPose? {
    val x = arguments.optionalDouble("x")
    val y = arguments.optionalDouble("y")
    val z = arguments.optionalDouble("z")
    if (x == null && y == null && z == null) return null
    if (x == null || y == null || z == null) throw ToolInputException("A camera position needs all of x, y and z")

    val lookAt = listOf("look_at_x", "look_at_y", "look_at_z").map(arguments::optionalDouble)
    val (yaw, pitch) = if (lookAt.all { it != null }) {
        val dx = lookAt[0]!! - x
        val dy = lookAt[1]!! - y
        val dz = lookAt[2]!! - z
        Math.toDegrees(atan2(-dx, dz)) to Math.toDegrees(atan2(-dy, sqrt(dx * dx + dz * dz)))
    } else {
        (arguments.optionalDouble("yaw") ?: 0.0) to (arguments.optionalDouble("pitch") ?: 0.0)
    }
    val fov = arguments.optionalDouble("fov")?.toFloat() ?: Minecraft.getInstance().options.fov().get().toFloat()
    return CameraPose(
        position = Vec3f(x.toFloat(), y.toFloat(), z.toFloat()),
        rotation = Vec3f(pitch.toFloat().coerceIn(-90f, 90f), yaw.toFloat(), 0f),
        fov = fov.coerceIn(MIN_FOV, MAX_FOV),
    )
}

private class EncodedImage(val png: ByteArray, val width: Int, val height: Int)

/** Scales [image] down to [maxWidth] when it is wider, and writes it as PNG. */
private fun encode(image: NativeImage, maxWidth: Int): EncodedImage {
    if (image.width <= maxWidth) return EncodedImage(image.asByteArray(), image.width, image.height)
    val height = (image.height.toLong() * maxWidth / image.width).toInt().coerceAtLeast(1)
    return NativeImage(maxWidth, height, false).use { scaled ->
        image.resizeSubRectTo(0, 0, image.width, image.height, scaled)
        EncodedImage(scaled.asByteArray(), maxWidth, height)
    }
}

private const val DEFAULT_WIDTH = 1280
private const val MIN_WIDTH = 64
private const val MAX_WIDTH = 3840
private const val MIN_FOV = 1f
private const val MAX_FOV = 170f
