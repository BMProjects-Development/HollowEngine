package ru.hollowhorizon.hollowengine.client.sounds

import net.minecraft.resources.FileToIdConverter
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceManager
import ru.hollowhorizon.hollowengine.common.sounds.SoundSource

/** Vanilla extensionless OGG ids remain stable; all other formats retain their extension. */
object SoundResourceConverter : FileToIdConverter("sounds", ".ogg") {
    private val extensions: Set<String> get() = SoundSource.fileExtensions + SoundDecoders.extensions
    override fun idToFile(id: ResourceLocation): ResourceLocation =
        if (id.path.substringAfterLast('.', "") in extensions) id.withPath("sounds/${id.path}")
        else super.idToFile(id)

    override fun fileToId(file: ResourceLocation): ResourceLocation =
        if (file.path.endsWith(".ogg")) super.fileToId(file)
        else file.withPath(file.path.removePrefix("sounds/"))

    override fun listMatchingResources(manager: ResourceManager): MutableMap<ResourceLocation, Resource> =
        manager.listResources("sounds") { it.path.substringAfterLast('.', "") in extensions }

    override fun listMatchingResourceStacks(manager: ResourceManager): MutableMap<ResourceLocation, MutableList<Resource>> =
        manager.listResourceStacks("sounds") { it.path.substringAfterLast('.', "") in extensions }
}
