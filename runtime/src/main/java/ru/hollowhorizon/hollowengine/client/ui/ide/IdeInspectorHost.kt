package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.*

/**
 * What the IDE offers the fields of its inspector and previews: the assets of the loaded packs, and a tree to pick one
 * from, which the IDE opens over everything. A target that needs more (the entity editor lists the scripts of the server)
 * provides a host of its own inside its content.
 */
internal object IdeInspectorHost : InspectorHost {
    var pending by mutableStateOf<InspectorAssetRequest?>(null)

    override val canPickAssets: Boolean get() = true

    override fun pickAsset(request: InspectorAssetRequest) {
        pending = request
    }
}

/** The inspector window: whatever was published last, with the IDE as its host. */
@Composable
internal fun IdeInspectorDock() {
    CompositionLocalProvider(LocalInspectorHost provides IdeInspectorHost) {
        InspectorPanel(
            target = InspectorSelection.current,
            empty = InspectorLang.nothingSelected,
            keepScroll = true,
        )
    }
}
