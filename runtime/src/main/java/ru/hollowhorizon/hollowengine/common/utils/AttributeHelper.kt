package ru.hollowhorizon.hollowengine.common.utils

import net.minecraft.world.entity.ai.attributes.AttributeMap
import ru.hollowhorizon.hollowengine.bridge.attributes.MutableAttributeMap
import ru.hollowhorizon.hollowengine.bridge.attributes.MutableAttributeState

val AttributeMap.mutableAttributes: MutableAttributeState
    get() = (this as MutableAttributeMap).hollowengineMutableAttributes()
