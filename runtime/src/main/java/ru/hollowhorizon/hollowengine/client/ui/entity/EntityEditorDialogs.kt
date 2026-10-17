package ru.hollowhorizon.hollowengine.client.ui.entity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.client.slots.ClientSlots
import ru.hollowhorizon.hollowengine.client.slots.PlayerInventory
import ru.hollowhorizon.hollowengine.client.slots.SlotGrid
import ru.hollowhorizon.hollowengine.client.slots.Slots
import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.inspector.InspectorIconButton
import ru.hollowhorizon.hollowengine.common.attachments.editor.EntityEditorSlots
import ru.hollowhorizon.hollowengine.common.ui.UiData
import ru.hollowhorizon.hollowengine.common.ui.UiScope

private val CenteredOnViewport = UiPopupAlignment(
    anchorHorizontal = UiAlign.CENTER,
    anchorVertical = UiAlign.CENTER,
    popupHorizontal = UiAlign.CENTER,
    popupVertical = UiAlign.CENTER,
)

@Composable
private fun EditorDialog(id: String, title: String, width: Float, onClose: () -> Unit, content: HollowUiContent) {
    val viewport = LocalUiViewport.current
    Popup(
        anchorBounds = viewport,
        alignment = CenteredOnViewport,
        id = "$id-popup",
        layer = 100,
        modal = true,
        onDismiss = onClose,
    ) {
        Column(
            id = id,
            tags = listOf("ee-dialog"),
            modifier = Modifier.size(width.px, UiLength.Fit)
                .maxSize(height = (viewport.height - 80f).coerceAtLeast(160f).px),
        ) {
            Row(tags = listOf("ee-dialog-head")) {
                Text(title, tags = listOf("ee-dialog-title"), modifier = Modifier.grow(1f))
                InspectorIconButton(EntityEditorIcons.CLOSE, EntityEditorLang.close) { onClose() }
            }
            content()
        }
    }
}

@Composable
internal fun InventoryDialog(session: EntityEditorSession) {
    val sessionId = session.slotSessionId ?: return
    val scope = remember(sessionId) { EntitySlotScope(sessionId, session::closeSlots) }

    EditorDialog("ee-inventory-dialog", EntityEditorLang.inventory, 380f, session::closeSlots) {
        Text(EntityEditorLang.inventoryHint, tags = listOf("insp-hint"))

        with(scope) {
            Slots(
                tags = listOf("ee-slots"),
                modifier = Modifier.size(100.percent, UiLength.Fit),
                dropOutside = false,
            ) {
                Column(tags = listOf("ee-slot-groups")) {
                    if (slotZoneExists(sessionId, EntityEditorSlots.EQUIPMENT)) {
                        Text(EntityEditorLang.equipment, tags = listOf("ee-section-title"))
                        SlotGrid(EntityEditorSlots.EQUIPMENT, columns = SlotsPerRow)
                    }
                    if (slotZoneExists(sessionId, EntityEditorSlots.INVENTORY)) {
                        Text(EntityEditorLang.carried, tags = listOf("ee-section-title"))
                        SlotGrid(EntityEditorSlots.INVENTORY, columns = SlotsPerRow)
                    }
                    Text(EntityEditorLang.playerItems, tags = listOf("ee-section-title"))
                    PlayerInventory()
                }
            }
        }
    }
}

private const val SlotsPerRow = 9

private fun slotZoneExists(sessionId: Int, zone: String): Boolean =
    ClientSlots[sessionId]?.layout?.zone(zone) != null

/**
 * The [UiScope] the slot widgets need, for a screen written in Kotlin rather than declared in a script.
 *
 * The document stays empty: this UI's state is the slots, and those have their own session-bound sync.
 */
private class EntitySlotScope(
    override val sessionId: Int,
    private val onClose: () -> Unit,
) : UiScope {
    override val data: UiData = UiData()

    override fun send(payload: CompoundTag) = Unit

    override fun close() = onClose()
}
