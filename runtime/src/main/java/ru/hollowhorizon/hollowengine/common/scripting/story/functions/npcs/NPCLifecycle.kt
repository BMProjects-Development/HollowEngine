package ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.common.attachments.api.set
import ru.hollowhorizon.hollowengine.common.attachments.components.Model
import ru.hollowhorizon.hollowengine.common.attachments.components.NameplateMode
import ru.hollowhorizon.hollowengine.common.attachments.components.TransformComponent
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.entities.setNameplate
import ru.hollowhorizon.hollowengine.common.npcs.inventory.NpcInventory
import ru.hollowhorizon.hollowengine.common.npcs.registry.npcs
import ru.hollowhorizon.hollowengine.common.utils.currentServer
import ru.hollowhorizon.hollowengine.common.utils.isValidRL
import ru.hollowhorizon.hollowengine.common.utils.math.TrsTransformF
import kotlin.contracts.ExperimentalContracts

/**
 * Создаёт и возвращает нового NPC (`NpcEntity`) в указанной позиции и с заданными параметрами внешнего вида, поведения и анимации.
 *
 * Эта функция используется для быстрой генерации NPC с полной кастомизацией: модель, текстуры, анимации, масштаб, имя, и прочее.
 * Она подходит как для статических, так и для интерактивных персонажей, включая анимированных мобов, актёров сцен и сюжетных героев.
 *
 * ### Пример использования:
 * ```
 * npc(
 *     pos = Vec3(10.0, 64.0, 10.0),
 *     name = "Guide",
 *     model = "my_mod:models/entity/guide.gltf"
 * )
 * ```
 *
 * @param pos Позиция размещения NPC в мире (в мировых координатах).
 * @param name Отображаемое имя NPC. Показывается над головой, если [nameplateMode] = NameplateMode.SHOW.
 * @param model Путь к 3D-модели в формате `.gltf`. Используется для рендеринга внешности NPC.
 * @param rotation Начальная ориентация NPC: `x` - yaw, `y` - pitch, в градусах.
 * @param world Идентификатор мира, в котором должен появиться NPC. Например: `"minecraft:overworld"`.
 * @param attributes Дополнительные числовые параметры NPC (например, `"health"` → `20f`, `"speed"` → `0.3f`), определяют поведение.
 * @param transform Локальные трансформации модели: смещение, поворот и масштаб по всем осям.
 * @param nameplateMode Initial nameplate visibility mode.
 * @param inverseHeadRotation Если `true`, голова NPC будет поворачиваться в противоположную сторону (например, для камеры от 3-го лица).
 * @param id Id автора, например `"village:blacksmith"`. Если живой NPC с таким id уже есть, возвращается он, а остальные
 * параметры не применяются: перезапуск сцены не плодит двойников. Если его чанк не загружен, возвращается сущность вне
 * мира, которая станет телом при загрузке чанка. Без id каждый вызов создаёт нового NPC.
 *
 * @return Созданный экземпляр [NpcEntity], добавленный в мир.
 */
@OptIn(ExperimentalContracts::class)
fun npc(
    pos: Vec3,
    name: String = "NPC",
    model: String = "hollowengine:models/entity/player_model.gltf",
    rotation: Vec2 = rotation(0, 0),
    world: String = "minecraft:overworld",
    attributes: Map<String, Float> = emptyMap(),
    transform: TrsTransformF = TrsTransformF(),
    nameplateMode: NameplateMode = NameplateMode.SHOW,
    inverseHeadRotation: Boolean = false,
    inventorySize: Int = NpcInventory.DEFAULT_SIZE,
    id: String? = null,
): NpcEntity {
    assert(model.isValidRL()) {
        "Non [a-z0-9/._-] character in path of location: $model"
    }

    val npcs = currentServer.npcs
    if (id != null) npcs.alive(id)?.let { return it }

    val level = currentServer.getLevel(currentServer.levelKeys().find { it.location().toString() == world }
        ?: throw IllegalStateException("Dimension $world not found!"))
        ?: throw IllegalStateException("Dimension $world is not loaded!")

    return NpcEntity(level).apply {
        inventory.resize(inventorySize)
        moveTo(pos.x, pos.y, pos.z, rotation.x, rotation.y)
        yHeadRot = rotation.x
        yBodyRot = rotation.x

        set(Model(model))
        set(TransformComponent(transform))

        if (attributes.isNotEmpty()) {
            setAttributes(attributes)
        }

        refreshDimensions()

        setNameplate(name, nameplateMode)

        npcs.spawn(this, id)
    }
}

/**
 * Keeps the chunks around the NPC loaded while [block] runs, for walks and other actions that must finish even
 * where no player is. [NpcEntity.isForceLoaded] does the same for good.
 */
suspend fun <R> NpcEntity.forceLoad(block: suspend NpcEntity.() -> R): R {
    forceLoadCalls++
    try {
        return block()
    } finally {
        forceLoadCalls--
    }
}

fun NpcEntity.despawn() {
    this.remove(Entity.RemovalReason.DISCARDED)
}

fun pos(x: Int, y: Int, z: Int) = Vec3(x + 0.5, y.toDouble(), z + 0.5)
fun pos(x: Double, y: Double, z: Double) = Vec3(x, y, z)
fun rotation(x: Int, y: Int) = Vec2(x.toFloat(), y.toFloat())
fun rotation(x: Float, y: Float) = Vec2(x, y)
