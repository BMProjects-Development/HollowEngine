package ru.hollowhorizon.hollowengine.common.addons

data class HollowAddonDescriptor(
    val id: String,
    val version: String,
    val entrypoint: String?,
    val dependencies: List<String> = emptyList(),
    val name: String = id,
    val environment: HollowAddonEnvironment = HollowAddonEnvironment.COMMON,
    val requiredClasses: List<String> = emptyList(),
    val mappingNamespace: HollowAddonMappingNamespace = HollowAddonMappingNamespace.AGNOSTIC,
)

enum class HollowAddonEnvironment {
    COMMON,
    CLIENT,
    SERVER;

    fun supports(isClient: Boolean): Boolean = when (this) {
        COMMON -> true
        CLIENT -> isClient
        SERVER -> !isClient
    }
}

enum class HollowAddonMappingNamespace(val id: String) {
    AGNOSTIC("agnostic"),
    OFFICIAL("official"),
    INTERMEDIARY("intermediary"),
    NAMED("named"),
}
