package ru.hollowhorizon.hollowengine.common.events

import mezz.jei.api.helpers.IPlatformFluidHelper
import mezz.jei.api.recipe.IRecipeManager
import mezz.jei.api.recipe.category.IRecipeCategory
import mezz.jei.api.registration.*
import mezz.jei.api.runtime.IJeiRuntime
import mezz.jei.api.runtime.config.IJeiConfigManager
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import ru.hollowhorizon.hollowengine.LOGGER
import ru.hollowhorizon.hollowengine.common.compat.util.hide
import ru.hollowhorizon.hollowengine.common.compat.util.hideWithin
import ru.hollowhorizon.hollowengine.common.compat.util.recipeCategoryId
import ru.hollowhorizon.hollowengine.common.compat.util.recipeManager
import ru.hollowhorizon.hollowengine.common.events.factory.EventHandler
import ru.hollowhorizon.hollowengine.common.utils.JavaHacks
import ru.hollowhorizon.hollowengine.common.utils.mcTranslate

open class ModifyRecipeViewerEvent : ClientEvent {
    class RegisterItemSubtypes(val reg: ISubtypeRegistration) : ModifyRecipeViewerEvent() {
        companion object : EventHandler<RegisterItemSubtypes>()
    }

    class RegisterFluidSubtypes(val reg: ISubtypeRegistration, val helper: IPlatformFluidHelper<*>) :
        ModifyRecipeViewerEvent() {
        companion object : EventHandler<RegisterFluidSubtypes>()
    }

    class RegisterIngredients(val reg: IModIngredientRegistration) : ModifyRecipeViewerEvent() {
        companion object : EventHandler<RegisterIngredients>()
    }

    class RegisterExtraIngredients(val reg: IExtraIngredientRegistration) : ModifyRecipeViewerEvent() {
        companion object : EventHandler<RegisterExtraIngredients>()
    }

    class RegisterIngredientAliases(val reg: IIngredientAliasRegistration) : ModifyRecipeViewerEvent() {
        companion object : EventHandler<RegisterIngredientAliases>()
    }

    class RegisterCategories(val reg: IRecipeCategoryRegistration) : ModifyRecipeViewerEvent() {
        companion object : EventHandler<RegisterCategories>()
    }

    class RegisterVanillaCategoryExtensions(val reg: IVanillaCategoryExtensionRegistration) :
        ModifyRecipeViewerEvent() {
        companion object : EventHandler<RegisterVanillaCategoryExtensions>()
    }

    class RegisterRecipes(val reg: IRecipeRegistration) : ModifyRecipeViewerEvent() {
        fun addItemInfo(ingredient: ItemStack, vararg info: Component) {
            reg.addItemStackInfo(ingredient, *info)
        }

        fun addItemInfo(ingredient: ItemStack, info: List<Component>) {
            reg.addItemStackInfo(ingredient, *info.toTypedArray())
        }

        fun addItemInfo(ingredient: ItemStack, info: Component) {
            reg.addItemStackInfo(ingredient, info)
        }

        fun addItemInfo(ingredient: ItemStack, info: String) {
            reg.addItemStackInfo(ingredient, info.mcTranslate)
        }

        fun addItemInfo(ingredient: ItemStack, info: Array<String>) {
            reg.addItemStackInfo(ingredient, *info.map { it.mcTranslate }.toTypedArray())
        }

        companion object : EventHandler<RegisterRecipes>()
    }

    class RegisterRecipeTransferHandlers(val reg: IRecipeTransferRegistration) : ModifyRecipeViewerEvent() {
        companion object : EventHandler<RegisterRecipeTransferHandlers>()
    }

    class RegisterRecipeCatalysts(val reg: IRecipeCatalystRegistration) : ModifyRecipeViewerEvent() {
        companion object : EventHandler<RegisterRecipeCatalysts>()
    }

    class RegisterGuiHandlers(val reg: IGuiHandlerRegistration) : ModifyRecipeViewerEvent() {
        companion object : EventHandler<RegisterGuiHandlers>()
    }

    class RegisterAdvanced(val reg: IAdvancedRegistration) : ModifyRecipeViewerEvent() {
        companion object : EventHandler<RegisterAdvanced>()
    }

    class RegisterRuntime(val reg: IRuntimeRegistration) : ModifyRecipeViewerEvent() {
        companion object : EventHandler<RegisterRuntime>()
    }

    class RegisterOnRuntimeAvailable(val jeiRuntime: IJeiRuntime) : ModifyRecipeViewerEvent() {
        fun hideRecipe(categoryId: ResourceLocation, recipeId: ResourceLocation) {
            val manager = jeiRuntime.recipeManager
            manager.createRecipeCategoryLookup()
                .includeHidden()
                .get()
                .filter { categoryId == it.recipeCategoryId }
                .findAny()
                .ifPresent { this.hide(recipeId, categoryId, manager, it) }
        }

        fun hideCategory(categoryId: ResourceLocation) {
            val manager = jeiRuntime.recipeManager
            manager.createRecipeCategoryLookup()
                .includeHidden()
                .get()
                .filter { categoryId == it.recipeCategoryId }
                .findAny()
                .ifPresent { it.hide(manager) }
        }

        private fun <T> hide(
            recipeId: ResourceLocation,
            categoryId: ResourceLocation,
            manager: IRecipeManager,
            category: IRecipeCategory<T>,
        ) {
            recipeManager.byKey(recipeId).ifPresent { this.hide(recipeId, categoryId, manager, category, it) }
        }

        private fun <T, U> hide(
            recipeId: ResourceLocation,
            categoryId: ResourceLocation,
            manager: IRecipeManager,
            category: IRecipeCategory<T>,
            recipe: U,
        ) {
            try {
                category.hideWithin(JavaHacks.forceCast(recipe), manager)
            } catch (e: IllegalArgumentException) {
                LOGGER.error(
                    "Unable to hide target recipe '$recipeId' within category '$categoryId' due to an error; maybe the recipe is not removable?",
                    e
                )
            }
        }

        companion object : EventHandler<RegisterOnRuntimeAvailable>()
    }

    class RegisterOnConfigManagerAvailable(val configManager: IJeiConfigManager) : ModifyRecipeViewerEvent() {
        companion object : EventHandler<RegisterOnConfigManagerAvailable>()
    }
}
