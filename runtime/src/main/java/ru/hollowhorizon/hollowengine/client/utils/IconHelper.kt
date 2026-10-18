package ru.hollowhorizon.hollowengine.client.utils

import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource
import ru.hollowhorizon.hollowengine.common.utils.rl
import ru.hollowhorizon.hollowengine.generated.Assets

object IconHelper {
    val Icons = Assets.Hollowengine.Textures.Gui.Icons

    fun forPath(path: String, isFolder: Boolean = false, isOpened: Boolean = false): ResourceLocation {
        return when {
            isFolder -> {
                if(isOpened) {
                    when (path) {
                        "assets" -> Icons.FOLDER_ASSETS_OPEN
                        "data" -> Icons.FOLDER_DATA_OPEN
                        "scripts" -> Icons.FOLDER_SCRIPTS_OPEN
                        "npcs" -> Icons.FOLDER_NPCS_OPEN
                        "camera" -> Icons.FOLDER_CAMERA_OPEN
                        else -> Icons.FOLDER_OPEN
                    }
                } else {
                    when (path) {
                        "assets" -> Icons.FOLDER_ASSETS
                        "data" -> Icons.FOLDER_DATA
                        "scripts" -> Icons.FOLDER_SCRIPTS
                        "npcs" -> Icons.FOLDER_NPCS
                        "camera" -> Icons.FOLDER_CAMERA
                        else -> Icons.FOLDER
                    }
                }
            }

            else -> forFile(path)
        }
    }

    /**
     * The icon of a file, by what it is: the engine's own formats first, since several of them end in a
     * suffix another format also uses (`.node.kts` is a `.kts`, `.geo.json` is a `.json`).
     */
    fun forFile(path: String): ResourceLocation {
        val name = path.substringAfterLast('/').lowercase()
        val icon = FileIcons.firstOrNull { (suffixes, _) -> suffixes.any(name::endsWith) }?.second ?: if (name.substringAfterLast('.') in SoundSource.fileExtensions) "sound" else "file"
        return "hollowengine:textures/gui/icons/files/$icon.svg".rl
    }

    private val FileIcons: List<Pair<List<String>, String>> = listOf(
        listOf(".node.kts") to "script_node",
        listOf(".ui.kts") to "script_ui",
        listOf(".startup.kts") to "script_startup",
        listOf(".reload.kts") to "script_reload",
        listOf(".mixin.kts") to "script_mixin",
        listOf(".kts", ".kt") to "script",
        listOf(".vfx") to "effect",
        listOf(".material") to "shader",
        listOf(".animator") to "animator",
        listOf(".rig") to "rig",
        listOf(".story") to "story",
        listOf(".hss") to "style",
        listOf(".bc") to "blocks",
        listOf(".gltf", ".glb", ".fbx", ".geo.json", ".obj") to "model",
        listOf(".json", ".json5", ".mcmeta") to "data",
        listOf(".png", ".jpg", ".jpeg", ".gif") to "image",
        listOf(".ogg", ".mp3", ".wav") to "sound",
        listOf(".mp4", ".avi", ".mov") to "video",
        listOf(".zip", ".rar", ".jar") to "archive",
        listOf(".txt", ".md", ".yml", ".yaml", ".toml", ".properties", ".lang", ".cfg") to "text",
    )
}
