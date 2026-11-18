package ru.hollowhorizon.hollowengine.client.ui.ide.preview

import ru.hollowhorizon.hollowengine.client.ui.ide.files.sounds.SoundsPreview
import ru.hollowhorizon.hollowengine.client.ui.ide.files.sounds.isSoundsFile
import ru.hollowhorizon.hollowengine.client.ui.ide.recipe.RecipePreview
import ru.hollowhorizon.hollowengine.client.ui.ide.recipe.isRecipeFile

internal fun HollowIdePreviewRegistry.registerBuiltinPreviews() {
    register(languageTablePreview)
    register(
        HollowIdeFilePreview(
            id = "sounds",
            defaultMode = HollowIdeViewMode.PREVIEW,
            matcher = { path -> path.isSoundsFile() },
            content = { context -> SoundsPreview(context) },
        ),
    )
    register(
        HollowIdeFilePreview(
            id = "recipe",
            defaultMode = HollowIdeViewMode.SPLIT,
            matcher = { path -> path.isRecipeFile() },
            content = { context -> RecipePreview(context) },
        ),
    )
}
