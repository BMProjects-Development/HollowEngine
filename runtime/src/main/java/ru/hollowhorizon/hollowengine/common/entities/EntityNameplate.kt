package ru.hollowhorizon.hollowengine.common.entities

import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.attachments.api.set
import ru.hollowhorizon.hollowengine.common.attachments.components.NameplateComponent
import ru.hollowhorizon.hollowengine.common.attachments.components.NameplateMode
import ru.hollowhorizon.hollowengine.common.attachments.components.nameplateComponent
import ru.hollowhorizon.hollowengine.common.utils.literal

fun Entity.setNameplate(text: String, mode: NameplateMode = NameplateMode.SHOW) {
    customName = text.takeIf(String::isNotBlank)?.literal
    nameplateMode = mode
}

var Entity.nameplateMode: NameplateMode
    get() = nameplateComponent?.mode ?: if (isCustomNameVisible) NameplateMode.SHOW else NameplateMode.HIDDEN
    set(value) {
        isCustomNameVisible = value == NameplateMode.SHOW && customName != null
        set(NameplateComponent(value))
    }