package ru.hollowhorizon.hollowengine.common.utils

import ru.hollowhorizon.hollowengine.api.ModList
import java.io.File

object ModList : ModList {
    private lateinit var modList: ModList

    fun init(modList: ModList) {
        this.modList = modList
    }

    override fun isLoaded(modId: String): Boolean {
        return modList.isLoaded(modId)
    }

    override fun getFile(modId: String): File {
        return modList.getFile(modId)
    }

    override fun getMods(): List<ModList.ModInfo> {
        return modList.mods
    }

    fun getAllFiles(modId: String): List<File> = listOf(getFile(modId))
}