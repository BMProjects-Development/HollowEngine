package ru.hollowhorizon.hollowengine.addons.narrative.graph


interface StoryParamTrait {
    data class Choices(val values: List<String>) : StoryParamTrait
    data class Asset(val extensions: List<String>) : StoryParamTrait
    data class RegistryEntry(val registry: String, val tags: Boolean = false) : StoryParamTrait
    data class Widget(val id: String) : StoryParamTrait {
        companion object {
            const val COLOR = "color"
            const val INVENTORY_SLOT = "inventory-slot"
        }
    }

    data object Animation : StoryParamTrait
    data class Model(val fallback: String? = null) : StoryParamTrait
    data object NpcId : StoryParamTrait

    companion object {
        fun choices(values: List<String>): List<StoryParamTrait> =
            if (values.isEmpty()) emptyList() else listOf(Choices(values))
    }
}
