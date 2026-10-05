package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.client.editor.WorldHistory
import ru.hollowhorizon.hollowengine.client.history.SnapshotStep
import ru.hollowhorizon.hollowengine.client.history.UndoLabel
import ru.hollowhorizon.hollowengine.client.models.internal.manager.RigAssets
import ru.hollowhorizon.hollowengine.client.ui.ide.files.rig.RigEditing
import ru.hollowhorizon.hollowengine.common.attachments.api.AttachmentRegistry
import ru.hollowhorizon.hollowengine.common.attachments.components.Model
import ru.hollowhorizon.hollowengine.common.attachments.editor.SetEntityComponentsPacket
import ru.hollowhorizon.hollowengine.common.models.ModelRig

/**
 * What one entity hangs on its model, edited in place.
 */
internal class EntityRigEditing(val entityId: Int, initial: Model) : RigEditing {
    private var model = initial

    override var rig by mutableStateOf(initial.rig)
        private set

    /**
     * Edits sent and not yet seen coming back, oldest first.
     */
    private val sent = ArrayDeque<ModelRig>()

    /** The rig changed here since it was last sent. */
    private var unsent = false
    private var sentAtNanos = 0L

    override val history get() = WorldHistory.history

    override val occupied: ModelRig
        get() = RigAssets.of(ResourceLocation.tryParse(model.model)).overlay(rig)

    override fun edit(mergeKey: String?, label: UndoLabel?, change: (ModelRig) -> ModelRig) {
        val next = change(rig)
        if (next == rig) return
        WorldHistory.record(entityId, SnapshotStep(this, label ?: UndoLabel.EDIT, rig, { rig }, ::restore), mergeKey)
        apply(next)
    }

    override fun beginGesture() = history.begin()

    override fun endGesture() {
        history.commit()
        flush()
    }

    /** Puts back a rig from the history; it goes to the server at once, as no gesture ends after it. */
    private fun restore(previous: ModelRig) {
        apply(previous)
        flush()
    }

    /** Shows [next] at once; the server hears of it with the next [flush]. */
    private fun apply(next: ModelRig) {
        rig = next
        model = model.copy(rig = next)
        if (!putLocally()) return
        unsent = true
        if (System.nanoTime() - sentAtNanos >= SEND_INTERVAL_NANOS) flush()
    }

    /**
     * Sends the rig when it changed since it last went. A drag changes it every frame, and every change
     * would go to the server and from there to everyone who sees the entity, so it goes at most once a tick.
     */
    fun flush() {
        if (!unsent) return
        unsent = false
        sentAtNanos = System.nanoTime()
        sent.addLast(rig)
        while (sent.size > MAX_IN_FLIGHT) sent.removeFirst()
        SetEntityComponentsPacket(entityId, listOf(model)).send()
    }

    private fun putLocally(): Boolean {
        val entity = Minecraft.getInstance().level?.getEntity(entityId) ?: return false
        AttachmentRegistry.attachments(entity).components.put(model)
        return true
    }

    /** The entity's model as the server just sent it. */
    fun accept(current: Model) {
        val echoed = sent.indexOf(current.rig)
        if (echoed >= 0 || current.rig == rig) {
            repeat(echoed + 1) { sent.removeFirst() }
            model = current.copy(rig = rig)
            if (current.rig != rig) putLocally()
            return
        }
        model = current
        rig = current.rig
        forget()
    }

    /** Let's go of everything this session remembers, when it is dropped or its entity changed elsewhere. */
    fun forget() {
        unsent = false
        sent.clear()
        WorldHistory.forget(entityId)
    }

    private companion object {
        const val MAX_IN_FLIGHT = 256

        const val SEND_INTERVAL_NANOS = 50_000_000L
    }
}
