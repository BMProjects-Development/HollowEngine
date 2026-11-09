package ru.hollowhorizon.hollowengine.client.editor

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.models.internal.Model
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.common.scripting.ide.AnimationClips

/**
 * The clips of the models the client has loaded, for the analysis thread. What is not known yet is asked for, and the
 * next analysis sees the answer.
 */
object GameAnimationClips : AnimationClips {
    override fun of(model: String): List<String>? {
        val location = ResourceLocation.tryParse(model) ?: return null
        if (!HollowModelManager.supports(location)) return null
        return HollowModelManager.getOrCreate(location).value.takeIf { it !== Model.EMPTY }?.animations?.map { it.name }
    }

    override fun modelOfNpc(id: String): String? {
        Minecraft.getInstance().execute { WorldNpcModels.of(id) }
        return WorldNpcModels.known(id)?.model
    }
}
