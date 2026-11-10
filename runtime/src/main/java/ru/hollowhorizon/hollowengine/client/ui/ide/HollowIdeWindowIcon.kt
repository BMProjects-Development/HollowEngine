package ru.hollowhorizon.hollowengine.client.ui.ide

import com.mojang.blaze3d.platform.IconSet
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWImage
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memAlloc
import org.lwjgl.system.MemoryUtil.memFree
import ru.hollowhorizon.hollowengine.HollowEngine
import java.io.IOException
import java.nio.ByteBuffer

internal fun applyMinecraftWindowIcon(window: Long) {
    val platform = GLFW.glfwGetPlatform()
    if (platform == GLFW.GLFW_PLATFORM_COCOA || platform == GLFW.GLFW_PLATFORM_WAYLAND) return

    val iconSet = if (SharedConstants.getCurrentVersion().isStable) IconSet.RELEASE else IconSet.SNAPSHOT
    val pixels = mutableListOf<ByteBuffer>()
    try {
        val icons = iconSet.getStandardIcons(Minecraft.getInstance().vanillaPackResources)
        MemoryStack.stackPush().use { stack ->
            val images = GLFWImage.malloc(icons.size, stack)
            icons.forEachIndexed { index, icon ->
                icon.get().use { stream ->
                    NativeImage.read(stream).use { image ->
                        val buffer = memAlloc(image.width * image.height * Int.SIZE_BYTES)
                        pixels += buffer
                        buffer.asIntBuffer().put(image.pixelsRGBA)
                        images[index].set(image.width, image.height, buffer)
                    }
                }
            }
            GLFW.glfwSetWindowIcon(window, images)
        }
    } catch (failure: IOException) {
        HollowEngine.LOGGER.warn("Could not load Minecraft's icon for a detached IDE window", failure)
    } finally {
        pixels.forEach(::memFree)
    }
}
